/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._
import akka.stream.ActorFlowMaterializer
import akka.stream.OverflowStrategy
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit

class ActorRefSourceSpec extends AkkaSpec {
  implicit val mat = ActorFlowMaterializer()

  "A ActorRefSource" must {

    "emit received messages to the stream" in {
      val s = StreamTestKit.SubscriberProbe[Int]()
      val ref = Source.actorRef.to(Sink(s)).run()
      val sub = s.expectSubscription
      sub.request(2)
      ref ! 1
      s.expectNext(1)
      ref ! 2
      s.expectNext(2)
      ref ! 3
      s.expectNoMsg(500.millis)
    }

    "be useful together with buffer" in {
      val s = StreamTestKit.SubscriberProbe[Int]()
      // FIXME isn't there a race when starting up, if I send to the ref before the buffer has requested?
      //       Do we ignore that and fix that with fusing, or shall I add an internal buffer to the ActorRefSourceActor?
      val ref = Source.actorRef
        .buffer(100, OverflowStrategy.DropTail).withAttributes(OperationAttributes.inputBuffer(initial = 128, max = 128))
        .to(Sink(s)).run()
      val sub = s.expectSubscription
      for (n ← 1 to 110) ref ! n
      sub.request(20)
      for (n ← 1 to 20) s.expectNext(n)
      s.expectNoMsg(300.millis)
      sub.request(100)
      for (n ← 21 to 100) s.expectNext(n)
    }

    "terminate when the stream is cancelled" in {
      val s = StreamTestKit.SubscriberProbe[Int]()
      val ref = Source.actorRef.to(Sink(s)).run()
      watch(ref)
      val sub = s.expectSubscription
      sub.cancel()
      expectTerminated(ref)
    }
  }
}
