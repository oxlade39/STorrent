package io.github.oxlade39.storrent.logger

import org.slf4j.LoggerFactory

/**
 * @author dan
 */
trait Logging { self =>
  val logger = LoggerFactory.getLogger(self.getClass)
}
