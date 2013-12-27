package io.github.oxlade39.storrent.test.util

import akka.actor.{Terminated, Props, ActorRef, Actor}
import akka.event.LoggingReceive

/**
 * @author dan
 */
class StepParent(child: Props, fwd: ActorRef) extends Actor {
  context.watch(context.actorOf(child, "child"))
  def receive = LoggingReceive {
    case Terminated(_) => context.stop(self)
    case msg           => fwd.tell(msg, sender)
  }
}

class FosterParent(childProps: Props, probe: ActorRef) extends Actor {
  val child = context.actorOf(childProps, "child")
  def receive = {
    case msg if sender == context.parent =>
      probe forward msg
      child forward msg
    case msg =>
      probe forward msg
      context.parent forward msg
  }
}

class ForwardingParent(childProps: Props, forwardTo: ActorRef) extends Actor {
  val child = context.watch(context.actorOf(childProps, "child"))
  def receive = {
    case Terminated(_) => context.stop(self)

    case msg if sender == child =>
      forwardTo forward msg
      context.parent forward msg
    case msg =>
      child forward msg
  }
}