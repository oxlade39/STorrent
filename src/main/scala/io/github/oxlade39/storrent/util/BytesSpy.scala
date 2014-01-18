package io.github.oxlade39.storrent.util

import akka.actor.{ActorLogging, Props, Actor}
import akka.io.Tcp
import java.io.{FileOutputStream, FileWriter, File}
import akka.util.ByteString

/**
 * @author dan
 */
object BytesSpy {
  def props(file: File) = Props(new BytesSpy(file))
}

class BytesSpy(file: File) extends Actor with ActorLogging {
  log.info("writing to {}", file.getAbsolutePath)
  file.mkdir()

  var sequence = 0

  def receive = {
    case b: ByteString => {
      val fw = new FileOutputStream(new File(file, s"$sequence.out"))
      try {
        fw.write(b.toArray)
        fw.flush()
      } finally {
        sequence += 1
        fw.close()
      }
    }

  }
}