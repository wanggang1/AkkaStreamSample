package org.gwgs


import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import org.gwgs.http.server.lowlever.StartingStopping
import org.gwgs.stream._

object Main {
  
  def main(args: Array[String]) = {
  
    implicit val system = ActorSystem("akka-stream", ConfigFactory.load().getConfig("akka"))
    implicit val materializer = ActorMaterializer()

/////////////////// Akka Stream ////////////////////////////////////////////////
//    QuickStart.run
    
//    Basics.basic
//    Basics.wireup
    
//    Graphs.graph  
//    Graphs.graphReuse
//    Graphs.partialGraph
//    Graphs.simpleSource
//    Graphs.simpleFlow
//    Graphs.simplifedAPI
//    Graphs.customizeShape
//    Graphs.materializedValue
    
//    Modularity.runnableGraph
//    Modularity.partialGraph
    
//    Buffers.internalBuffers //to run this, comment out system.shutdown
    
//    CustomProcessing.graphStage
//    CustomProcessing.pushPull

//    ActorIntegration.actorPublish
//    ActorIntegration.actorSubscriber
    
//    ExternalIntegration.externalOrdered
//    ExternalIntegration.externalUnOrdered
//    ExternalIntegration.externalBlocking
//    ExternalIntegration.externalActor
//    ExternalIntegration.ordered
//    ExternalIntegration.unOrdered

//    ReactiveStreamIntegration.otherStream
    
//    ErrorHandling.default
//    ErrorHandling.overrideWithResume
//    ErrorHandling.overrideInFlow
//    ErrorHandling.overrideWithRestart
//    ErrorHandling.fromExternal
    
    //make the main Thread.sleep(2000) when running StreamIO
//    StreamIO.tcpServer 
//    StreamIO.replClient
//    StreamIO.replClient2
//    StreamIO.fileIO
    
//    TestStream.testBuildIns
//    TestStream.testViaAkkaTestkit1
//    TestStream.testViaAkkaTestkit2
//    TestStream.testViaAkkaTestkit3
//    TestStream.testViaStreamTestkit1
//    TestStream.testViaStreamTestkit2
//    TestStream.testViaStreamTestkit3
//    TestStream.testViaStreamTestkit4
////////////////////////////////////////////////////////////////////////////////    
    
    
////////////////// Akka HTTP ///////////////////////////////////////////////////
//    StartingStopping.start
    StartingStopping.startWithHandler
    
////////////////////////////////////////////////////////////////////////////////

    //added for CustomProcessing.pushPull, and some other long running ones.
    Thread.sleep(2000000)
    system.shutdown
    
    //always return Unit last, to prevent something from accidentally returned
    ()
  }
  
}
