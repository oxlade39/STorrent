package io.github.oxlade39.storrent.workpulling

import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Actor, Props, ActorSystem}
import org.scalatest.matchers.MustMatchers
import akka.pattern.ask
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import concurrent.duration._

/**
 * @author dan
 */
class WorkPullingPatternTest extends TestKit(ActorSystem("WorkPullingPatternTest"))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender with MustMatchers {
  import WorkPullingPatternTest._

  "the working pulling pattern" should {
    "request work when available" in {

      val reports = TestProbe()
      implicit val timeout = Timeout(1, TimeUnit.SECONDS)

      val workSizer = system.actorOf(Props(new Actor {
        def receive = {
          case work: Work =>
            sender ! work.size
        }         
      }))
      val workReporter = system.actorOf(Props(new Actor {
        def receive = {
          case size: Int =>
            reports.ref ! size
        }
      }))
      
      val master = system.actorOf(Props(new Master[Work]))
      val worker = system.actorOf(Props(new Worker[Work, Int](master) {
        def doWork(work: Work) = for {
          size <- (workSizer ? work).mapTo[Int]
        } yield {
          workReporter ! size
          size
        }
      }))

      master ! "string with size 19"
      reports.expectMsg(19)
    }
  }
}

object WorkPullingPatternTest {
  type Work = String
}