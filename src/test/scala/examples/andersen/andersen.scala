package examples.andersen

import datalog.dsl.{Constant, Program}
import tools.TestGenerator

import java.nio.file.Paths

class andersen extends TestGenerator(
 Paths.get("src", "test", "scala", "examples", "andersen") // TODO: use pwd
) {
 override val toSolve = "pointsTo"
 def pretest(program: Program): Unit = {
  val addressOf = program.namedRelation("addressOf")
  val assign = program.namedRelation("assign")
  val load = program.namedRelation("load")
  val store = program.namedRelation("store")

  val x, y, z, w = program.variable()
  val pointsTo = program.relation[Constant]("pointsTo")

  pointsTo(y, x) :- addressOf(y, x)

  pointsTo(y, x) :- (assign(y, z), pointsTo(z, x))

  pointsTo(y, w) :- (
    load(y, x),
    pointsTo(x, z),
    pointsTo(z, w))

  pointsTo(z, w) :- (
    store(y, x),
    pointsTo(y, z),
    pointsTo(x, w))
 }
}
