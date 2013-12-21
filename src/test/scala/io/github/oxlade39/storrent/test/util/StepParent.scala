package io.github.oxlade39.storrent.test.util

import akka.actor.{Terminated, Props, ActorRef, Actor}

/**
 * @author dan
 */
class StepParent(child: Props, fwd: ActorRef) extends Actor {
  context.watch(context.actorOf(child, "child"))
  def receive = {
    case Terminated(_) => context.stop(self)
    case msg           => fwd.tell(msg, sender)
  }
}
