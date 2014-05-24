import adept.sbt._

scalaVersion := "2.10.3"

scalacOptions += "-feature"

scalacOptions += "-deprecation"

val scalatestDep = "org.scalatest" %% "scalatest" % "2.0" % "test"

incOptions := incOptions.value.withNameHashing(true)


lazy val adeptLockfile = project.in(file("adept-lockfile")).settings(
  name := "adept-lockfile",
  version := "0.9.2.1",
  organization := "com.adepthub",
  autoScalaLibrary in Test := false,
  crossPaths in Test := false //, 
  // libraryDependencies ++= Seq(
  //   "net.minidev" % "json-smart" % "1.2",
  //   scalatestDep
  // )
).settings(AdeptPlugin.adeptSettings: _*).settings(
  AdeptKeys.adeptLockfiles := {
      val AdeptLockfileFilePattern = """(.*)\.adept""".r
      println(((baseDirectory.value / "project" / "adept")).getAbsolutePath)
      println(((baseDirectory.value / "project" / "adept") ** "*.adept").get)
      ((baseDirectory.value / "project" / "adept") ** "*.adept").get.flatMap { file =>
        if (file.isFile()) {
          file.getName match {
            case AdeptLockfileFilePattern(conf) =>
              Some(conf -> file)
          }
        } else {
          None
        }
      }.toMap
    }
)


lazy val adeptCore = project.in(file("adept-core")).settings(
  name := "adept-core",
  version := "0.9.2.1",
  organization := "com.adepthub",
  //jgit
  resolvers += "Jgit Repository" at "https://repo.eclipse.org/content/groups/releases/",
  //play?
  resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  resolvers += Resolver.url("Typesafe Repository (non maven compat)",  url("http://repo.typesafe.com/typesafe/releases"))(Resolver.ivyStylePatterns) //,
  // libraryDependencies ++= Seq(
  //   "org.eclipse.jgit" % "org.eclipse.jgit" % 	"3.1.0.201310021548-r",
  //   "net.sf.ehcache" % "ehcache-core" % "2.6.6", //needed by adept.repository.RepositoryEngine
  //   "javax.transaction" % "jta" % "1.1", //needed by ehcache
  //   "com.typesafe.play" %% "play-json" % "2.2.1",
  //   scalatestDep)
).dependsOn(adeptLockfile).settings(AdeptPlugin.adeptSettings: _*).settings(
  AdeptKeys.adeptLockfiles := {
      val AdeptLockfileFilePattern = """(.*)\.adept""".r
      ((baseDirectory.value / "project" / "adept") ** "*.adept").get.flatMap { file =>
        if (file.isFile()) {
          file.getName match {
            case AdeptLockfileFilePattern(conf) =>
              Some(conf -> file)
          }
        } else {
          None
        }
      }.toMap
    }
)

