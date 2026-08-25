scalaVersion := "2.12.13"

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xsource:2.11",
  "-language:reflectiveCalls"
)

resolvers ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  Resolver.sonatypeRepo("releases")
)

// Single consistent version set — remove the duplicate 3.6.0 block
libraryDependencies += "edu.berkeley.cs" %% "chisel3"    % "3.5.6"
libraryDependencies += "edu.berkeley.cs" %% "chiseltest" % "0.5.6" % "test"

addCompilerPlugin(
  "edu.berkeley.cs" % "chisel3-plugin" % "3.5.6" cross CrossVersion.full
)

// Heap for compilation
javaOptions ++= Seq("-Xmx8g", "-Xms2g", "-XX:+UseG1GC")
fork := true   // REQUIRED — javaOptions only apply when forked