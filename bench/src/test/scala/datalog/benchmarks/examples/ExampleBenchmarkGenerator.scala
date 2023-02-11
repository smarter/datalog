package datalog.benchmarks.examples

import datalog.benchmarks.DLBenchmark
import datalog.dsl.{Constant, Program, Relation, Term}
import datalog.execution.{NaiveExecutionEngine, NaiveStagedExecutionEngine, SemiNaiveExecutionEngine, SemiNaiveStagedExecutionEngine, SemiNaiveInterpretedStagedExecutionEngine}
import datalog.storage.{CollectionsStorageManager, RelationalStorageManager}

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.io.Source
import scala.jdk.StreamConverters.*
import scala.util.Properties

inline val examples_warmup_iterations = 10
inline val examples_iterations = 5
inline val examples_warmup_time = 10
inline val examples_time = 10
inline val examples_batchSize = 1
inline val examples_fork = 1
inline val examples_xl_time = 60

abstract class ExampleBenchmarkGenerator(testname: String,
                                         val skip: Set[String] = Set(),
                                         override val tags: Set[String] = Set()) extends BenchmarkGenerator(
  Paths.get("..", "src", "test", "scala", "test", "examples", testname),
  skip, tags
)

abstract class BenchmarkGenerator(directory: Path,
                             skip: Set[String] = Set(),
                             val tags: Set[String] = Set()) extends DLBenchmark {
  def pretest(program: Program): Unit
  val description: String = directory.getFileName.toString

  // import EDBs and IDBs
  private val factdir = Paths.get(directory.toString, "facts")
  if (Files.exists(factdir))
    Files.walk(factdir, 1)
      .filter(p => Files.isRegularFile(p))
      .forEach(f => {
        val edbName = f.getFileName.toString.replaceFirst("[.][^.]+$", "")
        val reader = Files.newBufferedReader(f)
        val headers = reader.readLine().split("\t")
        val edbs = reader.lines()
          .map(l => {
            val factInput = l
              .split("\t")
              .zipWithIndex.map((s, i) =>
                (headers(i) match {
                  case "Int" => s.toInt
                  case "String" => s
                  case _ => throw new Error(s"Unknown type ${headers(i)}")
                }).asInstanceOf[Term]
              ).toSeq
            if (factInput.length != headers.size)
              throw new Error(s"Input data for fact of length ${factInput.length} but should be ${headers.mkString("[", ", ", "]")}. Line='$l'")
            factInput
          }).toScala(Seq)
        reader.close()
        inputFacts(edbName) = edbs
      })
  // Generate expected
  private val expDir = Paths.get(directory.toString, "expected")
  if (!Files.exists(expDir)) throw new Exception(s"Missing expected directory '$expDir'")
  Files.walk(expDir, 1)
    .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".csv"))
    .forEach(f => {
      val rule = f.getFileName.toString.replaceFirst("[.][^.]+$", "")
      val reader = Files.newBufferedReader(f)
      val headers = reader.readLine().split("\t")
      val expected = reader.lines()
        .map(l => l.split("\t").zipWithIndex.map((s, i) =>
          (headers(i) match {
            case "Int" => s.toInt
            case "String" => s
            case _ => throw new Error(s"Unknown type ${headers(i)}")
          }).asInstanceOf[Term]
        ).toSeq)
        .toScala(Set)
      expectedFacts(rule) = expected
      reader.close()
    })

  Seq("SemiNaive", "Naive", "NaiveStaged", "SemiNaiveStaged", "SemiNaiveJITStaged", "SemiNaiveInterpretedStaged").foreach(execution =>
    Seq("Relational", "Collections").foreach(storage =>
      if (
        (execution.contains("Staged") && storage == "Relational") ||
          skip.contains(execution) || skip.contains(storage) ||
          (tags ++ Set(execution, storage)).flatMap(t => Properties.envOrNone(t.toUpperCase())).nonEmpty
      ) {}
      else {
        programs(s"$execution$storage") = initialize(s"$execution$storage")
      }))

}