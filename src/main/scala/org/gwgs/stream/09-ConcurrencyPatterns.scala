package org.gwgs

import akka.stream.FlowShape
import akka.stream.scaladsl._


object ConcurrencyPatterns {

  /*
   * STEPs dependency:
   * fryingPan1 does not need to wait on fringPan2 to finish cooking the Pancake,
   * BUT needs to wait on fryingPan2 to take the HalfCookedPancake before it can
   * take a new ScoopOfBatter.
   * 
   * The benefit of pipelining is that it can be applied to any sequence of processing
   * steps that are otherwise not parallelisable (one depends on the other).
   * 
   * One drawback is that if the processing times of the stages are very different then
   * some of the stages will not be able to operate at full throughput because they will
   * wait on a previous or subsequent stage most of the time.  In the pancake example
   * frying the second half of the pancake is usually faster than frying the first half,
   * fryingPan2 will not be able to operate at full capacity.
   */
  def pipeLine() = {
    // Takes a scoop of batter and creates a pancake with one side cooked
    val fryingPan1: Flow[ScoopOfBatter, HalfCookedPancake, Unit] =
      Flow[ScoopOfBatter].map { batter => HalfCookedPancake() }

    // Finishes a half-cooked pancake
    val fryingPan2: Flow[HalfCookedPancake, Pancake, Unit] =
      Flow[HalfCookedPancake].map { halfCooked => Pancake() }

    // With the two frying pans we can fully cook pancakes
    val pancakeChef: Flow[ScoopOfBatter, Pancake, Unit] =
      Flow[ScoopOfBatter].via(fryingPan1).via(fryingPan2)
  }
 
  /*
   * The benefit of parallelizing is that it is easy to scale. In the pancake example
   * it is easy to add a third frying pan with Patrik's method, but Roland cannot add
   * a third frying pan, since that would require a third processing step, which is not
   * practically possible in the case of frying pancakes. 
   * 
   * One drawback of the example code below is that it does not preserve the ordering
   * of pancakes. This might be a problem if children like to track their "own" pancakes.
   * In those cases the Balance and Merge stages should be replaced by strict-round 
   * robing balancing and merging stages that put in and take out pancakes in a strict order.
   */
  def parallel() = {
    import GraphDSL.Implicits._
    
    val fryingPan: Flow[ScoopOfBatter, Pancake, Unit] =
      Flow[ScoopOfBatter].map { batter => Pancake() }
 
    val pancakeChef: Flow[ScoopOfBatter, Pancake, Unit] = 
      Flow.fromGraph(GraphDSL.create() { implicit builder =>
        val dispatchBatter = builder.add(Balance[ScoopOfBatter](2))
        val mergePancakes = builder.add(Merge[Pancake](2))

        // Using two frying pans in parallel, both fully cooking a pancake from the batter.
        // We always put the next scoop of batter to the first frying pan that becomes available.
        dispatchBatter.out(0) ~> fryingPan ~> mergePancakes.in(0)
        
        // Notice that we used the "fryingPan" flow without importing it via builder.add().
        // Flows used this way are auto-imported, which in this case means that the two
        // uses of "fryingPan" mean actually different stages in the graph.
        dispatchBatter.out(1) ~> fryingPan ~> mergePancakes.in(1)

        FlowShape(dispatchBatter.in, mergePancakes.out)
      })
  }
  
  /*
   * This pattern works well if there are many independent JOBs that do not depend on
   * the results of each other, but the JOBs themselves need multiple processing STEPs
   * where each STEP builds on the result of the previous one. In our case individual
   * pancakes do not depend on each other, they can be cooked in parallel, on the other
   * hand it is not possible to fry both sides of the same pancake at the same time, so
   * the two sides have to be fried in sequence.
   */
  def combinePipelineIntoParallel() = {
    import GraphDSL.Implicits._
    
    val fryingPan1: Flow[ScoopOfBatter, HalfCookedPancake, Unit] =
      Flow[ScoopOfBatter].map { batter => HalfCookedPancake() }
    
    val fryingPan2: Flow[HalfCookedPancake, Pancake, Unit] =
      Flow[HalfCookedPancake].map { halfCooked => Pancake() }
    
    val pancakeChef: Flow[ScoopOfBatter, Pancake, Unit] =
      Flow.fromGraph(GraphDSL.create() { implicit builder =>

        val dispatchBatter = builder.add(Balance[ScoopOfBatter](2))
        val mergePancakes = builder.add(Merge[Pancake](2))

        // Using two pipelines, having two frying pans each, in total using
        // four frying pans
        dispatchBatter.out(0) ~> fryingPan1 ~> fryingPan2 ~> mergePancakes.in(0)
        dispatchBatter.out(1) ~> fryingPan1 ~> fryingPan2 ~> mergePancakes.in(1)

        FlowShape(dispatchBatter.in, mergePancakes.out)
      })
  }
  
  /*
   * This usage pattern is less common but might be usable if a certain step in the
   * pipeline might take wildly different times to finish different jobs. The reason
   * is that there are more balance-merge steps in this pattern compared to the 
   * parallel pipelines. This pattern rebalances after each step, while the previous
   * pattern only balances at the entry point of the pipeline. This only matters
   * however if the processing time distribution has a large deviation.
   */
  def combineParallelIntoPipeline() = {
    import GraphDSL.Implicits._
    
    val fryingPan1: Flow[ScoopOfBatter, HalfCookedPancake, Unit] =
      Flow[ScoopOfBatter].map { batter => HalfCookedPancake() }
    
    val fryingPan2: Flow[HalfCookedPancake, Pancake, Unit] =
      Flow[HalfCookedPancake].map { halfCooked => Pancake() }
    
    val pancakeChefs1: Flow[ScoopOfBatter, HalfCookedPancake, Unit] =
      Flow.fromGraph(GraphDSL.create() { implicit builder =>
        val dispatchBatter = builder.add(Balance[ScoopOfBatter](2))
        val mergeHalfPancakes = builder.add(Merge[HalfCookedPancake](2))

        // Two chefs each work with one frying pan, half-frying the pancakes then putting
        // them into a common pool
        dispatchBatter.out(0) ~> fryingPan1 ~> mergeHalfPancakes.in(0)
        dispatchBatter.out(1) ~> fryingPan1 ~> mergeHalfPancakes.in(1)

        FlowShape(dispatchBatter.in, mergeHalfPancakes.out)
      })

    val pancakeChefs2: Flow[HalfCookedPancake, Pancake, Unit] =
      Flow.fromGraph(GraphDSL.create() { implicit builder =>
        val dispatchHalfPancakes = builder.add(Balance[HalfCookedPancake](2))
        val mergePancakes = builder.add(Merge[Pancake](2))

        // Two chefs each work with one frying pan, finishing the pancakes then putting
        // them into a common pool
        dispatchHalfPancakes.out(0) ~> fryingPan2 ~> mergePancakes.in(0)
        dispatchHalfPancakes.out(1) ~> fryingPan2 ~> mergePancakes.in(1)

        FlowShape(dispatchHalfPancakes.in, mergePancakes.out)
      })

    val kitchen: Flow[ScoopOfBatter, Pancake, Unit] = pancakeChefs1.via(pancakeChefs2)
  } 
    
  case class ScoopOfBatter()
  case class HalfCookedPancake()
  case class Pancake()
}
