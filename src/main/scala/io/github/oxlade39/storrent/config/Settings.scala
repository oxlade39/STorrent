package io.github.oxlade39.storrent.config

import java.util.concurrent.TimeUnit

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration

class Settings(config: Config) extends Extension {
  val MaxPeerConnections: Int = config.getInt("peers.max")
  val PeerCheckStatusDuration: FiniteDuration = {
    val duration = config.getDuration("peers.checkStatusDuration")
    FiniteDuration(duration.toMillis, TimeUnit.MILLISECONDS)
  }
  val MaxDownloadWorkers: Int = config.getInt("download.maxWorkers")
  val MaxConcurrentRequestsPerPeer: Int = config.getInt("download.maxConcurrentRequestsPerPeer")
}

object Settings extends ExtensionId[Settings] with ExtensionIdProvider {
  override def lookup: Settings.type = Settings

  override def createExtension(system: ExtendedActorSystem) = new Settings(system.settings.config)
}