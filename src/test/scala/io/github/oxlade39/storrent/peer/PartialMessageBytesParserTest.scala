package io.github.oxlade39.storrent.peer

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import akka.testkit.{TestKit, TestProbe}
import akka.util.ByteString
import org.scalatest.{MustMatchers, WordSpecLike}

class PartialMessageBytesParserTest extends TestKit(ActorSystem("PartialMessageBytesParserTest"))
  with WordSpecLike with MustMatchers {
  "PartialMessageBytesParser" must {
    "parse PartialMessages from ByteString" in {
      for (msg <- TestMessages.exampleMessages) {
        val pm = PartialMessage(msg.length, msg.messageId, msg.payload.getOrElse(ByteString.empty))

        val out = PartialMessageBytesParser.toPartialMessage(pm.toByteString)
        assert(out.length == pm.length)
        assert(out.messageId == pm.messageId)
        assert(out.body == pm.body)
      }
    }
  }
}

class MessageParserTest extends TestKit(ActorSystem("MessageParserTest"))
  with WordSpecLike with MustMatchers {
  "MessageParser" must {
    "parse simple partial message" in {
      for (msg <- TestMessages.exampleMessages) {
        val partialMsg = PartialMessage(msg.length, msg.messageId, msg.payload.getOrElse(ByteString.empty))
        val out = MessageParser.parser(partialMsg)
        assert(out == msg)
      }
    }
  }
}

class ReactiveMessageParsingTest extends TestKit(ActorSystem("ReactiveMessageParsingTest"))
  with WordSpecLike with MustMatchers {
  "ReactiveMessageParsing" must {
    "parse PartialMessages from ByteString" in {

      implicit val mat: ActorMaterializer = ActorMaterializer()
      val probe = TestProbe()

      val bytesQueue = ReactiveMessageParsing.byteStringParserQueue
        .toMat(Sink.foreach(msg => probe.ref ! msg))(Keep.left)
        .run()

      for (msg <- TestMessages.exampleMessages) {
        val pm = PartialMessage(msg.length, msg.messageId, msg.payload.getOrElse(ByteString.empty))

        bytesQueue.offer(pm.toByteString)
        val out = probe.expectMsgType[Message]
        assert(out == msg)
      }
    }
  }
}


object TestMessages {
  val exampleMessages = Seq(
    KeepAlive,
    Cancel(9, 7, 1005),
    Choke,
    Have(457),
    Interested,
    NotInterested,
    Piece(7, 13, ByteString("hello world")),
    Port(8080),
    Request(24, 1, 57),
    UnChoke,
    Bitfield(Seq(true, false, true, true, false, false, true, false))
  )
}