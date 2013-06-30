package adept.core.operations

import adept.core.models._
import adept.utils.Logging
import adept.core.Adept
import adept.utils.EitherUtils

import collection.{ Set => _, _ }


/**
 * Matches artifacts and dependencies to configurations
 * 
 * NOTE: uses mutable collections. this makes it easier to update, but it break threadsafty
 * Therefore, these operations must not leak outside  
 */
private[operations] object ConfigurationMatcher extends Logging {

  import ConfigurationResolver._
  
  /**
   *  The artifacts matching a configuration expression for a given module
   */
  def matchingArtifacts(artifacts: Set[Artifact], configurations: Set[Configuration]): (mutable.Set[Artifact], mutable.Set[EvictedArtifact]) = {
    val foundExtendedConfs = configurations ++ configurations.flatMap(c => extendedConfs(configurations, c) )
    
    val all = foundExtendedConfs.flatMap{ foundConf =>
      artifacts.map{ artifact => 
        val artifactMatched = artifact.configurations.contains(foundConf.name)
        if (artifactMatched) Some(artifact) -> None
        else None -> Some(EvictedArtifact(artifact, 
            reason = "could not find any configurations for '" + artifact.configurations.mkString(",") + "' from " + configurations.map(_.name).mkString(",")))
      }
    }
    
    val (artifactOpts, evictedOpts) = all.unzip
    val foundArtifacts: mutable.Set[Artifact] = artifactOpts.collect{ case Some(a) => a }(breakOut)
    val evictedArtifacts: mutable.Set[EvictedArtifact]  = evictedOpts.collect{ case Some(e) => e }(breakOut)
    foundArtifacts -> evictedArtifacts.filter(ea => !foundArtifacts.contains(ea.artifact)) 
  }
  
  /**
   * The modules and matching configurations for a set of dependencies and a configuration expression
   */
  def matchingModules(parent: Coordinates, dependencies: Set[Dependency], rootExclusionRules: Set[DependencyExclusionRule], configurations: Set[Configuration], configurationMapping: String => String, findModule: (Coordinates, Option[Hash]) => Option[Module]): (mutable.Set[(Module, Set[DependencyExclusionRule], mutable.Set[Configuration])], mutable.Set[EvictedModule], Set[MissingDependency]) ={
    //We are using mutable variables here, because it makes the code easier to read and it improves speed
    //TODO: will probably be more efficient to use actors instead of synchronizition?
    var moduleConfs = new mutable.HashSet[(Module, Set[DependencyExclusionRule], mutable.Set[Configuration])] with mutable.SynchronizedSet[(Module, Set[DependencyExclusionRule], mutable.Set[Configuration])]
    var evicted = new mutable.HashSet[EvictedModule] with mutable.SynchronizedSet[EvictedModule]
    var missing = new mutable.HashSet[MissingDependency] with mutable.SynchronizedSet[MissingDependency]
      
    dependencies.par.foreach{ dependency => //TODO: check if .par makes things faster. we do this because of findModule which reads from disk. perhaps we should use a IO execution context
      val maybeModule = findModule(dependency.coordinates, None) //FIXME: we are not using the hash/unique Id here and that is very wrong!
      
      maybeModule match {
        case Some(module) =>
          logger.debug("found module for dependency: " + dependency)
          val matchingExclusions = rootExclusionRules.filter(_.matches(dependency)) 
          if (matchingExclusions.isEmpty) {
            val mappedConf = configurationMapping(dependency.configuration)
            
            resolve(configurations, mappedConf, module.configurations) match {
              case Right(confs) => moduleConfs += ((module, rootExclusionRules ++ dependency.exclusionRules, confs))
              case Left(msg) => evicted += EvictedModule( module, "no matching configurations: "+ msg.mkString(";"))
            }
          } else {
            logger.debug("excluding dependency " + dependency + " because of matching exclusion rules " + matchingExclusions)
            evicted += EvictedModule(module, "excluding: " + dependency.coordinates + " because of " + matchingExclusions.mkString(","))
          }
        case None =>
          logger.error("could not find module for: " + dependency)
          missing += MissingDependency(dependency, parent, reason = "could not find dependency: " + dependency.coordinates + " declared in: " + parent)
      }
    }
    
    (moduleConfs, evicted, missing.toSet)
  }
}
