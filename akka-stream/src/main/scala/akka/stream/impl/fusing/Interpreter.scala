/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import scala.annotation.tailrec
import scala.collection.breakOut
import scala.util.control.NonFatal
import akka.stream.stage._
import akka.stream.Supervision
import akka.stream.impl.ReactiveStreamsCompliance
import akka.stream.FlowMaterializer

// TODO:
// fix jumpback table with keep-going-on-complete ops (we might jump between otherwise isolated execution regions)
// implement grouped, buffer
// add recover

/**
 * INTERNAL API
 *
 * `BoundaryStage` implementations are meant to communicate with the external world. These stages do not have most of the
 * safety properties enforced and should be used carefully. One important ability of BoundaryStages that they can take
 * off an execution signal by calling `ctx.exit()`. This is typically used immediately after an external signal has
 * been produced (for example an actor message). BoundaryStages can also kickstart execution by calling `enter()` which
 * returns a context they can use to inject signals into the interpreter. There is no checks in place to enforce that
 * the number of signals taken out by exit() and the number of signals returned via enter() are the same -- using this
 * stage type needs extra care from the implementer.
 *
 * BoundaryStages are the elements that make the interpreter *tick*, there is no other way to start the interpreter
 * than using a BoundaryStage.
 */
private[akka] abstract class BoundaryStage extends AbstractStage[Any, Any, Directive, Directive, BoundaryContext] {
  def enter(): BoundaryContext = context

  final override def decide(t: Throwable): Supervision.Directive = Supervision.Stop

  final override def restart(): BoundaryStage =
    throw new UnsupportedOperationException("BoundaryStage doesn't support restart")
}

/**
 * INTERNAL API
 */
private[akka] object OneBoundedInterpreter {
  final val Debug = false

  /**
   * INTERNAL API
   *
   * This artificial op is used as a boundary to prevent two forked paths of execution (complete, cancel) to cross
   * paths again. When finishing an op this op is injected in its place to isolate upstream and downstream execution
   * domains.
   */
  private[akka] object Finished extends BoundaryStage {
    override def onPush(elem: Any, ctx: BoundaryContext): UpstreamDirective = ctx.finish()
    override def onPull(ctx: BoundaryContext): DownstreamDirective = ctx.finish()
    override def onUpstreamFinish(ctx: BoundaryContext): TerminationDirective = ctx.exit()
    override def onDownstreamFinish(ctx: BoundaryContext): TerminationDirective = ctx.exit()
    override def onUpstreamFailure(cause: Throwable, ctx: BoundaryContext): TerminationDirective = ctx.exit()
  }
}

/**
 * INTERNAL API
 *
 * One-bounded interpreter for a linear chain of stream operations (graph support is possible and will be implemented
 * later)
 *
 * The ideas in this interpreter are an amalgamation of earlier ideas, notably:
 *  - The original effect-tracking implementation by Johannes Rudolph -- the difference here that effects are not chained
 *  together as classes but the callstack is used instead and only certain combinations are allowed.
 *  - The on-stack reentrant implementation by Mathias Doenitz -- the difference here that reentrancy is handled by the
 *  interpreter itself, not user code, and the interpreter is able to use the heap when needed instead of the
 *  callstack.
 *  - The pinball interpreter by Endre Sándor Varga -- the difference here that the restricition for "one ball" is
 *  lifted by using isolated execution regions, completion handling is introduced and communication with the external
 *  world is done via boundary ops.
 *
 * The design goals/features of this interpreter are:
 *  - bounded callstack and heapless execution whenever possible
 *  - callstack usage should be constant for the most common ops independently of the size of the op-chain
 *  - allocation-free execution on the hot paths
 *  - enforced backpressure-safety (boundedness) on user defined ops at compile-time (and runtime in a few cases)
 *
 * The main driving idea of this interpreter is the concept of 1-bounded execution of well-formed free choice Petri
 * nets (J. Desel and J. Esparza: Free Choice Petri Nets - https://www7.in.tum.de/~esparza/bookfc.html). Technically
 * different kinds of operations partition the chain of ops into regions where *exactly one* event is active all the
 * time. This "exactly one" property is enforced by proper types and runtime checks where needed. Currently there are
 * three kinds of ops:
 *
 *  - PushPullStage implementations participate in 1-bounded regions. For every external non-completion signal these
 *  ops produce *exactly one* signal (completion is different, explained later) therefore keeping the number of events
 *  the same: exactly one.
 *
 *  - DetachedStage implementations are boundaries between 1-bounded regions. This means that they need to enforce the
 *  "exactly one" property both on their upstream and downstream regions. As a consequence a DetachedStage can never
 *  answer an onPull with a ctx.pull() or answer an onPush() with a ctx.push() since such an action would "steal"
 *  the event from one region (resulting in zero signals) and would inject it to the other region (resulting in two
 *  signals). However DetachedStages have the ability to call ctx.hold() as a response to onPush/onPull which temporarily
 *  takes the signal off and stops execution, at the same time putting the op in a "holding" state. If the op is in a
 *  holding state it contains one absorbed signal, therefore in this state the only possible command to call is
 *  ctx.pushAndPull() which results in two events making the balance right again:
 *  1 hold + 1 external event = 2 external event
 *  This mechanism allows synchronization between the upstream and downstream regions which otherwise can progress
 *  independently.
 *
 *  - BoundaryStage implementations are meant to communicate with the external world. These ops do not have most of the
 *  safety properties enforced and should be used carefully. One important ability of BoundaryStages that they can take
 *  off an execution signal by calling ctx.exit(). This is typically used immediately after an external signal has
 *  been produced (for example an actor message). BoundaryStages can also kickstart execution by calling enter() which
 *  returns a context they can use to inject signals into the interpreter. There is no checks in place to enforce that
 *  the number of signals taken out by exit() and the number of signals returned via enter() are the same -- using this
 *  op type needs extra care from the implementer.
 *  BoundaryStages are the elements that make the interpreter *tick*, there is no other way to start the interpreter
 *  than using a BoundaryStage.
 *
 * Operations are allowed to do early completion and cancel/complete their upstreams and downstreams. It is *not*
 * allowed however to do these independently to avoid isolated execution islands. The only call possible is ctx.finish()
 * which is a combination of cancel/complete.
 * Since onComplete is not a backpressured signal it is sometimes preferable to push a final element and then immediately
 * finish. This combination is exposed as pushAndFinish() which enables op writers to propagate completion events without
 * waiting for an extra round of pull.
 * Another peculiarity is how to convert termination events (complete/failure) into elements. The problem
 * here is that the termination events are not backpressured while elements are. This means that simply calling ctx.push()
 * as a response to onUpstreamFinished() will very likely break boundedness and result in a buffer overflow somewhere.
 * Therefore the only allowed command in this case is ctx.absorbTermination() which stops the propagation of the
 * termination signal, and puts the op in a finishing state. Depending on whether the op has a pending pull signal it has
 * not yet "consumed" by a push its onPull() handler might be called immediately.
 *
 * In order to execute different individual execution regions the interpreter uses the callstack to schedule these. The
 * current execution forking operations are
 *  - ctx.finish() which starts a wave of completion and cancellation in two directions. When an op calls finish()
 *  it is immediately replaced by an artificial Finished op which makes sure that the two execution paths are isolated
 *  forever.
 *  - ctx.fail() which is similar to finish()
 *  - ctx.pushAndPull() which (as a response to a previous ctx.hold()) starts a wawe of downstream push and upstream
 *  pull. The two execution paths are isolated by the op itself since onPull() from downstream can only answered by hold or
 *  push, while onPush() from upstream can only answered by hold or pull -- it is impossible to "cross" the op.
 *  - ctx.pushAndFinish() which is different from the forking ops above because the execution of push and finish happens on
 *  the same execution region and they are order dependent, too.
 * The interpreter tracks the depth of recursive forking and allows various strategies of dealing with the situation
 * when this depth reaches a certain limit. In the simplest case a failure is reported (this is very useful for stress
 * testing and finding callstack wasting bugs), in the other case the forked call is scheduled via a list -- i.e. instead
 * of the stack the heap is used.
 */
private[akka] class OneBoundedInterpreter(ops: Seq[Stage[_, _]],
                                          onAsyncInput: (AsyncContext[Any, Any], Any) ⇒ Unit,
                                          materializer: FlowMaterializer,
                                          val forkLimit: Int = 100,
                                          val overflowToHeap: Boolean = true,
                                          val name: String = "") {
  import OneBoundedInterpreter._
  type UntypedOp = AbstractStage[Any, Any, Directive, Directive, Context[Any]]
  require(ops.nonEmpty, "OneBoundedInterpreter cannot be created without at least one Op")

  private final val pipeline: Array[UntypedOp] = ops.map(_.asInstanceOf[UntypedOp])(breakOut)

  /**
   * This table is used to accelerate demand propagation upstream. All ops that implement PushStage are guaranteed
   * to only do upstream propagation of demand signals, therefore it is not necessary to execute them but enough to
   * "jump over" them. This means that when a chain of one million maps gets a downstream demand it is propagated
   * to the upstream *in one step* instead of one million onPull() calls.
   * This table maintains the positions where execution should jump from a current position when a pull event is to
   * be executed.
   */
  private final val jumpBacks: Array[Int] = calculateJumpBacks

  private final val Upstream = 0
  private final val Downstream = pipeline.length - 1

  // Var to hold the current element if pushing. The only reason why this var is needed is to avoid allocations and
  // make it possible for the Pushing state to be an object
  private var elementInFlight: Any = _
  // Points to the current point of execution inside the pipeline
  private var activeOpIndex = -1
  // Points to the last point of exit
  private var lastExitedIndex = Downstream
  // The current interpreter state that decides what happens at the next round
  private var state: State = _

  // Counter that keeps track of the depth of recursive forked executions
  private var forkCount = 0
  // List that is used as an auxiliary stack if fork recursion depth reaches forkLimit
  private var overflowStack = List.empty[(Int, State, Any)]

  private var lastOpFailing: Int = -1

  private def pipeName(op: UntypedOp): String = {
    val o = (op: AbstractStage[_, _, _, _, _])
    (o match {
      case Finished               ⇒ "finished"
      case _: BoundaryStage       ⇒ "boundary"
      case _: StatefulStage[_, _] ⇒ "stateful"
      case _: PushStage[_, _]     ⇒ "push"
      case _: PushPullStage[_, _] ⇒ "pushpull"
      case _: DetachedStage[_, _] ⇒ "detached"
      case _                      ⇒ "other"
    }) + s"(${o.holdingUpstream},${o.holdingDownstream},${o.terminationPending})"
  }
  override def toString =
    s"""|OneBoundedInterpreter($name)
        |  pipeline = ${pipeline map pipeName mkString ":"}
        |  lastExit=$lastExitedIndex activeOp=$activeOpIndex state=$state elem=$elementInFlight forks=$forkCount""".stripMargin

  @inline private def currentOp: UntypedOp = pipeline(activeOpIndex)

  // see the jumpBacks variable for explanation
  private def calculateJumpBacks: Array[Int] = {
    val table = Array.ofDim[Int](pipeline.length)
    var nextJumpBack = -1
    for (pos ← 0 until pipeline.length) {
      table(pos) = nextJumpBack
      if (!pipeline(pos).isInstanceOf[PushStage[_, _]]) nextJumpBack = pos
    }
    table
  }

  private def updateJumpBacks(lastNonCompletedIndex: Int): Unit = {
    var pos = lastNonCompletedIndex
    // For every jump that would jump over us we change them to jump into us
    while (jumpBacks(pos) < lastNonCompletedIndex && pos < pipeline.length) {
      jumpBacks(pos) = lastNonCompletedIndex
      pos += 1
    }
  }

  private sealed trait State extends DetachedContext[Any] with BoundaryContext with AsyncContext[Any, Any] {
    final def progress(): Unit = {
      advance()
      if (inside) run()
      else exit()
    }

    /**
     * Override this method to do execution steps necessary after executing an op, and advance the activeOpIndex
     * to another value (next or previous steps). Do NOT put code that invokes the next op, override run instead.
     */
    def advance(): Unit

    /**
     * Override this method to enter the current op and execute it. Do NOT put code that should be executed after the
     * op has been invoked, that should be in the advance() method of the next state resulting from the invokation of
     * the op.
     */
    def run(): Unit

    /**
     * This method shall return true iff this is a push-style state.
     */
    def comingFromUpstream: Boolean

    /**
     * This method shall return true iff this is a pull-style state.
     */
    def comingFromDownstream: Boolean

    /**
     * This method shall return true iff this is an async entry (“from the side”).
     */
    def comingFromSide: Boolean

    override def push(elem: Any): DownstreamDirective = {
      ReactiveStreamsCompliance.requireNonNullElement(elem)
      if (currentOp.isDetached) {
        if (comingFromUpstream)
          throw new IllegalStateException("Cannot push during onPush, only pull or pushAndPull")
        if (comingFromSide && !currentOp.holdingDownstream)
          throw new IllegalStateException("Cannot push during onAsyncInput unless isHoldingDownstream")
      }
      currentOp.precedingWasPull = false
      currentOp.holdingDownstream = false
      elementInFlight = elem
      state = Pushing
      null
    }

    override def pull(): UpstreamDirective = {
      if (currentOp.isDetached) {
        if (comingFromDownstream)
          throw new IllegalStateException("Cannot pull during onPull, only push or pushAndPull")
        if (comingFromSide && !currentOp.holdingUpstream)
          throw new IllegalStateException("Cannot push during onAsyncInput unless isHoldingUpstream")
      }
      currentOp.precedingWasPull = true
      currentOp.holdingUpstream = false
      state = Pulling
      null
    }

    override def getAsyncCallback(): AsyncCallback[Any] = {
      val current = currentOp.asInstanceOf[AsyncStage[Any, Any, Any]]
      val context = current.context // avoid concurrent access (to avoid @volatile)
      new AsyncCallback[Any] {
        override def invoke(evt: Any): Unit = onAsyncInput(context, evt)
      }
    }

    override def ignore(): AsyncDirective = {
      if (!comingFromSide) throw new IllegalStateException("Can only ignore from onAsyncInput")
      exit()
    }

    override def enter(evt: Any): Unit = ()

    override def finish(): FreeDirective = {
      fork(Completing)
      state = Cancelling
      null
    }

    def isFinishing: Boolean = currentOp.terminationPending

    override def pushAndFinish(elem: Any): DownstreamDirective = {
      ReactiveStreamsCompliance.requireNonNullElement(elem)
      if (currentOp.isDetached) {
        if (comingFromUpstream && !currentOp.holdingDownstream)
          throw new IllegalStateException("Cannot push from onPush unless isHoldingDownstream")
        if (comingFromSide && !currentOp.holdingDownstream)
          throw new IllegalStateException("Cannot push from onAsyncInput unless isHoldingDownStream")
      }
      currentOp.holdingDownstream = false
      currentOp.precedingWasPull = false
      pipeline(activeOpIndex) = Finished.asInstanceOf[UntypedOp]
      // This MUST be an unsafeFork because the execution of PushFinish MUST strictly come before the finish execution
      // path. Other forks are not order dependent because they execute on isolated execution domains which cannot
      // "cross paths". This unsafeFork is relatively safe here because PushAndFinish simply absorbs all later downstream
      // calls of pushAndFinish since the finish event has been scheduled already.
      // It might be that there are some degenerate cases where this can blow up the stack with a very long chain but I
      // am not aware of such scenario yet. If you know one, put it in InterpreterStressSpec :)
      unsafeFork(PushFinish, elem)
      elementInFlight = null
      finish()
    }

    override def fail(cause: Throwable): FreeDirective = {
      fork(Failing(cause))
      state = Cancelling
      null
    }

    override def holdUpstream(): UpstreamDirective = {
      currentOp.precedingWasPull = false
      currentOp.holdingUpstream = true
      exit()
    }

    override def holdUpstreamAndPush(elem: Any): UpstreamDirective = {
      ReactiveStreamsCompliance.requireNonNullElement(elem)
      if (comingFromUpstream && !currentOp.holdingDownstream)
        throw new IllegalStateException("Cannot holdUpstreamAndPush unless isHoldingDownstream")
      if (comingFromDownstream)
        throw new IllegalStateException("Cannot holdUpstreamAndPush during onPull")
      if (comingFromSide)
        throw new IllegalStateException("Cannot holdUpstreamAndPush during onAsyncInput")
      currentOp.precedingWasPull = false
      currentOp.holdingUpstream = true
      currentOp.holdingDownstream = false
      elementInFlight = elem
      state = Pushing
      null
    }

    override def isHoldingUpstream: Boolean = currentOp.holdingUpstream

    override def holdDownstream(): DownstreamDirective = {
      currentOp.holdingDownstream = true
      exit()
    }

    override def holdDownstreamAndPull(): DownstreamDirective = {
      if (comingFromUpstream)
        throw new IllegalStateException("Cannot holdDownstreamAndPull during onPush")
      if (comingFromDownstream && !currentOp.holdingUpstream)
        throw new IllegalStateException("Cannot holdDownstreamAndPull unless isHoldingUpstream")
      if (comingFromSide)
        throw new IllegalStateException("Cannot holdDownstreamAndPull during onAsyncInput")
      currentOp.precedingWasPull = true
      currentOp.holdingUpstream = false
      currentOp.holdingDownstream = true
      state = Pulling
      null
    }

    override def isHoldingDownstream: Boolean = currentOp.holdingDownstream

    override def pushAndPull(elem: Any): FreeDirective = {
      ReactiveStreamsCompliance.requireNonNullElement(elem)
      if (comingFromUpstream && !currentOp.holdingDownstream)
        throw new IllegalStateException("Cannot pushAndPull during onPush unless isHoldingDownstream")
      if (comingFromDownstream && !currentOp.holdingUpstream)
        throw new IllegalStateException("Cannot pushAndPull during onPull unless isHoldingUpstream")
      if (comingFromSide && !(currentOp.holdingUpstream && currentOp.holdingDownstream))
        throw new IllegalStateException("Cannot pushAndPull during onAsyncInput unless isHoldingBoth")
      currentOp.precedingWasPull = true
      currentOp.holdingUpstream = false
      currentOp.holdingDownstream = false
      fork(Pushing, elem)
      state = Pulling
      null
    }

    override def absorbTermination(): TerminationDirective = {
      updateJumpBacks(activeOpIndex)
      currentOp.holdingUpstream = false
      currentOp.holdingDownstream = false
      finish()
    }

    override def exit(): FreeDirective = {
      elementInFlight = null
      lastExitedIndex = activeOpIndex
      activeOpIndex = -1
      null
    }

    override def materializer: FlowMaterializer = OneBoundedInterpreter.this.materializer
  }

  private final val Pushing: State = new State {
    override def advance(): Unit = activeOpIndex += 1
    override def run(): Unit = currentOp.onPush(elementInFlight, ctx = this)

    override def comingFromUpstream = true
    override def comingFromDownstream = false
    override def comingFromSide = false

    override def toString = "Pushing"
  }

  private final val PushFinish: State = new State {
    override def advance(): Unit = activeOpIndex += 1
    override def run(): Unit = currentOp.onPush(elementInFlight, ctx = this)

    override def pushAndFinish(elem: Any): DownstreamDirective = {
      ReactiveStreamsCompliance.requireNonNullElement(elem)
      elementInFlight = elem
      state = PushFinish
      null
    }

    override def finish(): FreeDirective = {
      state = Completing
      null
    }

    override def comingFromUpstream = true
    override def comingFromDownstream = false
    override def comingFromSide = false

    override def toString = "PushFinish"
  }

  private final val Pulling: State = new State {
    override def advance(): Unit = {
      elementInFlight = null
      activeOpIndex = jumpBacks(activeOpIndex)
    }

    override def run(): Unit = currentOp.onPull(ctx = this)

    override def comingFromUpstream = false
    override def comingFromDownstream = true
    override def comingFromSide = false

    override def toString = "Pulling"
  }

  private final val Completing: State = new State {
    override def advance(): Unit = {
      elementInFlight = null
      pipeline(activeOpIndex) = Finished.asInstanceOf[UntypedOp]
      activeOpIndex += 1
    }

    override def run(): Unit = {
      if (!currentOp.terminationPending) currentOp.onUpstreamFinish(ctx = this)
      else exit()
    }

    override def finish(): FreeDirective = {
      state = Completing
      null
    }

    override def absorbTermination(): TerminationDirective = {
      currentOp.terminationPending = true
      currentOp.holdingUpstream = false
      updateJumpBacks(activeOpIndex)
      if (currentOp.holdingDownstream || (!currentOp.isDetached && currentOp.precedingWasPull)) {
        currentOp.holdingDownstream = false
        currentOp.onPull(ctx = Pulling)
      } else exit()
      null
    }

    override def comingFromUpstream = true
    override def comingFromDownstream = false
    override def comingFromSide = false

    override def toString = "Completing"
  }

  private final val Cancelling: State = new State {
    override def advance(): Unit = {
      elementInFlight = null
      pipeline(activeOpIndex) = Finished.asInstanceOf[UntypedOp]
      activeOpIndex -= 1
    }

    def run(): Unit = {
      if (!currentOp.terminationPending) currentOp.onDownstreamFinish(ctx = this)
      else exit()
    }

    override def finish(): FreeDirective = {
      state = Cancelling
      null
    }

    override def comingFromUpstream = false
    override def comingFromDownstream = true
    override def comingFromSide = false

    override def toString = "Cancelling"
  }

  private final case class Failing(cause: Throwable) extends State {
    override def advance(): Unit = {
      elementInFlight = null
      pipeline(activeOpIndex) = Finished.asInstanceOf[UntypedOp]
      activeOpIndex += 1
    }

    def run(): Unit = currentOp.onUpstreamFailure(cause, ctx = this)

    override def absorbTermination(): TerminationDirective = {
      currentOp.terminationPending = true
      currentOp.holdingUpstream = false
      updateJumpBacks(activeOpIndex)
      if (currentOp.holdingDownstream || (!currentOp.isDetached && currentOp.precedingWasPull)) {
        currentOp.holdingDownstream = false
        currentOp.onPull(ctx = Pulling)
      } else exit()
      null
    }

    override def comingFromUpstream = true
    override def comingFromDownstream = false
    override def comingFromSide = false
  }

  private def inside: Boolean = activeOpIndex > -1 && activeOpIndex < pipeline.length

  private def printDebug(): Unit = {
    val padding = "    " * activeOpIndex
    val icon: String = state match {
      case Pushing | PushFinish ⇒ padding + s"---> $elementInFlight"
      case Pulling ⇒
        ("    " * jumpBacks(activeOpIndex)) +
          "<---" +
          ("----" * (activeOpIndex - jumpBacks(activeOpIndex) - 1))
      case Completing ⇒ padding + "---|"
      case Cancelling ⇒ padding + "|---"
      case Failing(e) ⇒ padding + s"---X ${e.getMessage} => ${decide(e)}"
      case other      ⇒ padding + s"---? $state"
    }
    val holding = icon + (if (currentOp.holdingUpstream) 'U' else ' ') + (if (currentOp.holdingDownstream) 'D' else ' ')
    println(f"$holding%-24s $name")
  }

  @tailrec private def execute(): Unit = {
    while (inside) {
      try {
        if (Debug) printDebug()
        state.progress()
      } catch {
        case NonFatal(e) if lastOpFailing != activeOpIndex ⇒
          lastOpFailing = activeOpIndex
          decide(e) match {
            case Supervision.Stop ⇒ state.fail(e)
            case Supervision.Resume ⇒
              // reset, purpose of lastOpFailing is to avoid infinite loops when fail fails -- double fault
              lastOpFailing = -1
              state.pull()
            case Supervision.Restart ⇒
              // reset, purpose of lastOpFailing is to avoid infinite loops when fail fails -- double fault
              lastOpFailing = -1
              pipeline(activeOpIndex) = pipeline(activeOpIndex).restart().asInstanceOf[UntypedOp]
              state.pull()
          }
      }
    }

    // Execute all delayed forks that were put on the heap if the fork limit has been reached
    if (overflowStack.nonEmpty) {
      val memo = overflowStack.head
      activeOpIndex = memo._1
      state = memo._2
      elementInFlight = memo._3
      overflowStack = overflowStack.tail
      execute()
    }
  }

  def decide(e: Throwable): Supervision.Directive =
    if (state == Pulling || state.isHoldingUpstream || state.isHoldingDownstream) Supervision.Stop
    else currentOp.decide(e)

  /**
   * Forks off execution of the pipeline by saving current position, fully executing the effects of the given
   * forkState then setting back the position to the saved value.
   * By default forking is executed by using the callstack. If the depth of forking ever reaches the configured forkLimit
   * this method either fails (useful for testing) or starts using the heap instead of the callstack to avoid a
   * stack overflow.
   */
  private def fork(forkState: State, elem: Any = null): Unit = {
    forkCount += 1
    if (forkCount == forkLimit) {
      if (!overflowToHeap) throw new IllegalStateException("Fork limit reached")
      else overflowStack ::= ((activeOpIndex, forkState, elem))
    } else unsafeFork(forkState, elem)
    forkCount -= 1
  }

  /**
   * Unsafe fork always uses the stack for execution. This call is needed by pushAndComplete where the forked execution
   * is order dependent since the push and complete events travel in the same direction and not isolated by a boundary
   */
  private def unsafeFork(forkState: State, elem: Any = null): Unit = {
    val savePos = activeOpIndex
    elementInFlight = elem
    state = forkState
    execute()
    activeOpIndex = savePos
  }

  def init(): Unit = {
    initBoundaries()
    runDetached()
  }

  def isFinished: Boolean = pipeline(Upstream) == Finished && pipeline(Downstream) == Finished

  private class EntryState(name: String, position: Int) extends State {
    val entryPoint = position

    override def run(): Unit = ()
    override def advance(): Unit = ()

    override def enter(evt: Any): Unit = {
      activeOpIndex = entryPoint
      (currentOp: Any) match {
        case a: AsyncStage[Any, Any, Any] ⇒
          if (Debug) println("    " * entryPoint + "-ai- " + evt)
          a.onAsyncInput(evt, this)
        case other ⇒ throw new IllegalStateException("cannot enter() a non-AsyncStage")
      }
    }

    override def comingFromUpstream = false
    override def comingFromDownstream = false
    override def comingFromSide = true

    override def push(elem: Any): DownstreamDirective = {
      ReactiveStreamsCompliance.requireNonNullElement(elem)
      activeOpIndex = entryPoint
      super.push(elem)
      execute()
      null
    }

    override def pull(): UpstreamDirective = {
      activeOpIndex = entryPoint
      super.pull()
      execute()
      null
    }

    override def finish(): FreeDirective = {
      activeOpIndex = entryPoint
      super.finish()
      execute()
      null
    }

    override def fail(cause: Throwable): FreeDirective = {
      activeOpIndex = entryPoint
      super.fail(cause)
      execute()
      null
    }

    override def holdUpstream(): FreeDirective = {
      activeOpIndex = entryPoint
      super.holdUpstream()
      execute()
      null
    }

    override def holdDownstream(): FreeDirective = {
      activeOpIndex = entryPoint
      super.holdDownstream()
      execute()
      null
    }

    override def pushAndPull(elem: Any): FreeDirective = {
      ReactiveStreamsCompliance.requireNonNullElement(elem)
      activeOpIndex = entryPoint
      super.pushAndPull(elem)
      execute()
      null
    }

    override def toString = s"$name($entryPoint)"
  }

  /**
   * This method injects a Context to each of the BoundaryStages and AsyncStages. This will be the context returned by enter().
   */
  private def initBoundaries(): Unit = {
    var op = 0
    while (op < pipeline.length) {
      (pipeline(op): Any) match {
        case b: BoundaryStage ⇒
          b.context = new EntryState("boundary", op)
        case a: AsyncStage[Any, Any, Any] ⇒
          a.context = new EntryState("async", op)
          activeOpIndex = op
          a.initAsyncInput(a.context)
        case _ ⇒
      }
      op += 1
    }
  }

  /**
   * Starts execution of detached regions.
   *
   * Since detached ops partition the pipeline into different 1-bounded domains is is necessary to inject a starting
   * signal into these regions (since there is no external signal that would kick off their execution otherwise).
   */
  private def runDetached(): Unit = {
    var op = pipeline.length - 1
    while (op >= 0) {
      if (pipeline(op).isDetached) {
        activeOpIndex = op
        state = Pulling
        execute()
      }
      op -= 1
    }
  }

}
