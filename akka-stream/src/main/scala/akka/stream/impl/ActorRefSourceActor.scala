/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.ActorLogging
import akka.actor.Props

/**
 * INTERNAL API
 */
private[akka] object ActorRefSourceActor {
  val props = Props(new ActorRefSourceActor)
}

/**
 * INTERNAL API
 */
private[akka] class ActorRefSourceActor extends akka.stream.actor.ActorPublisher[Any] with ActorLogging {
  import akka.stream.actor.ActorPublisherMessage._

  def receive = {
    case Request(element) ⇒ // count is tracked by super
    case Cancel           ⇒ context.stop(self)
    case msg ⇒
      if (totalDemand > 0L && isActive)
        onNext(msg)
      else
        log.debug("Dropping element because there is no downstream demand: [{}]", msg)
  }

}
