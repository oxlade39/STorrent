package io.github.oxlade39.storrent.test.util

import scala.util.Random
import akka.util.ByteString

trait RandomOps {

  implicit class RandomOpsExt(r: Random.type) {

    def nextByteString(size: Int = r.nextInt(1024)): ByteString = {
      val arr = new Array[Byte](size)
      r.nextBytes(arr)
      ByteString(arr)
    }

  }

}
