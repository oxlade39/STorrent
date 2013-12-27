package io.github.oxlade39.storrent.peer

import org.scalatest.{BeforeAndAfterAll, WordSpecLike, FunSuite}
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import org.scalatest.mock.MockitoSugar
import org.scalatest.matchers.MustMatchers
import io.github.oxlade39.storrent.test.util.{ForwardingParent, FosterParent, StepParent}
import akka.io.Tcp
import akka.util.ByteString
import scala.util.Random
import concurrent.duration._

class HandshakerTest extends TestKit(ActorSystem("HandshakerTest"))
with WordSpecLike with BeforeAndAfterAll with ImplicitSender with MockitoSugar with MustMatchers {
  import HandshakerTest._

  "Handshaker" must {
    "send HandshakeSuccess when handshake parses successfully" in {
      val peerConnection = TestProbe()

      val handshaker = watch(system.actorOf(Props(new ForwardingParent(
        fakeHandshaker(parseResponse = Some(Handshake(fakeInfoHash, fakePeerId))), peerConnection.ref)), "HandshakeSuccess"))
      handshaker ! Tcp.Received(Handshake(fakeInfoHash, fakePeerId).encoded)
      peerConnection.expectMsg(Handshaker.HandshakeSuccess)
      expectTerminated(handshaker)
    }

    "send HandshakeFailure when handshake does not parse" in {
      val peerConnection = TestProbe()

      val handshaker = watch(system.actorOf(Props(new ForwardingParent(
        fakeHandshaker(parseResponse = None), peerConnection.ref)), "HandshakeFailed"))
      handshaker ! Tcp.Received(Handshake(fakeInfoHash, fakePeerId).encoded)
      peerConnection.expectMsg(Handshaker.HandshakeFailed)
      expectTerminated(handshaker)
    }

    "terminate after not receiving complete response within timeout" in {
      val peerConnection = TestProbe()

      val handshaker = watch(system.actorOf(Props(new ForwardingParent(
        fakeHandshaker(parseResponse = None), peerConnection.ref)), "HandshakeTimeout"))

      peerConnection.expectMsg(16.seconds, Handshaker.HandshakeFailed)
      expectTerminated(handshaker)
    }
  }

}

object HandshakerTest {
  import Handshake._

  val fakePeerId = PeerId()

  val fakeInfoHash: ByteString = {
    val bytes = new Array[Byte](20)
    Random.nextBytes(bytes)
    ByteString(bytes)
  }

  def fakeHandshaker(parseResponse: Option[Handshake]) = Props(new Handshaker {
    override def handshakeParser = new HandshakeParser {
      def parse(bs: ByteString) = parseResponse
    }
  })
}