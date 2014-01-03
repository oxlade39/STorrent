package io.github.oxlade39.storrent.peer

import org.scalatest.WordSpec
import akka.io.{PipelineContext, PipelinePorts, PipelineFactory}
import akka.util.ByteString
import org.scalatest.matchers.MustMatchers
import io.github.oxlade39.storrent.test.util.RandomOps
import scala.util.Random

class MessageTypeStageTest extends WordSpec with MustMatchers with RandomOps {
  import MessageTypeStageTest._

  "MessageTypeState" must {
    "parse bytes with single message" in {
      val stages = new MessageTypeStage

      val PipelinePorts(cmd, evt, mgmt) =
        PipelineFactory.buildFunctionTriple(new PipelineContext {}, stages)

      for (msg <- exampleMessages) {
        val pm = PartialMessage(msg.length, msg.messageId, msg.payload.getOrElse(ByteString.empty))
        val (output, _) = evt(msg.encode)
        assert(output.headOption == Some(pm), s"${output.headOption} != Some($pm) for $msg")
      }
    }

    "parse partialy complete multi messages" in {
      val PipelinePorts(cmd, evt, mgmt) =
        PipelineFactory.buildFunctionTriple(new PipelineContext {}, new MessageTypeStage)

      val msg = Piece(232, 11, Random.nextByteString())
      val partial = Piece(232, 11, Random.nextByteString()).encode.take(6)

      val pm = PartialMessage(msg.length, msg.messageId, msg.payload.getOrElse(ByteString.empty))

      val (output, _) = evt(msg.encode ++ partial)
      assert(output.headOption == Some(pm), s"${output.headOption} != Some($pm) for $msg")
    }

    "completes when partial messages complete" in {
      val PipelinePorts(cmd, evt, mgmt) =
        PipelineFactory.buildFunctionTriple(new PipelineContext {}, new MessageTypeStage)

      val msg = Piece(232, 11, Random.nextByteString())
      val partialMsg = Piece(232, 11, Random.nextByteString())
      val partial = partialMsg.encode.take(6)

      val pm = PartialMessage(partialMsg.length, partialMsg.messageId, partialMsg.payload.getOrElse(ByteString.empty))

      evt(msg.encode ++ partial)
      val (output, _) = evt(partialMsg.encode.drop(6))
      assert(output.headOption == Some(pm), s"${output.headOption} != Some($pm) for $msg")
    }
  }
}

object MessageTypeStageTest {
  val exampleMessages = Seq(
    KeepAlive,
    Bitfield(Seq(true, false, true, true, false)),
    Cancel(9, 7, 1005),
    Choke,
    Have(457),
    Interested,
    NotInterested,
    Piece(7, 13, ByteString("hello world")),
    Port(8080),
    Request(24, 1, 57),
    UnChoke
  )
}