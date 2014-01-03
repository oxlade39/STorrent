package io.github.oxlade39.storrent.peer

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import io.github.oxlade39.storrent.test.util.RandomOps
import akka.io.{PipelineContext, PipelineFactory, PipelinePorts}
import scala.util.Random
import akka.util.ByteString

/**
 * @author dan
 */
class MessageStageTest extends WordSpec with MustMatchers with RandomOps {
  import MessageTypeStageTest._

  "MessageStage" must {
    "parse simple partial message" in {
      val PipelinePorts(cmd, evt, mgmt) =
        PipelineFactory.buildFunctionTriple(new PipelineContext {}, new MessageStage)

      for (msg <- exampleMessages) {
        val partialMsg = PartialMessage(msg.length, msg.messageId, msg.payload.getOrElse(ByteString.empty))

        val (out, in) = evt(partialMsg)
        assert(out.headOption == Some(msg), s"${out.headOption} != Some($msg)")
      }
    }
  }
}
