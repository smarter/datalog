package all

import datalog.dsl.{Program, Relation}
import datalog.execution.{ExecutionEngine, NaiveExecutionEngine, SemiNaiveExecutionEngine}
import datalog.storage.{CollectionsStorageManager, IndexedCollStorageManager, RelationalStorageManager}
import graphs.{Acyclic, MultiIsolatedCycle, MultiJoin, RecursivePath, SingleCycle, TopSort}

class SemiNaiveRelationalTransitiveClosure extends munit.FunSuite {
  List(
    Acyclic(new Program(new SemiNaiveExecutionEngine(new RelationalStorageManager()))),
    MultiIsolatedCycle(new Program(new SemiNaiveExecutionEngine(new RelationalStorageManager()))),
    SingleCycle(new Program(new SemiNaiveExecutionEngine(new RelationalStorageManager()))),
    RecursivePath(new Program(new SemiNaiveExecutionEngine(new RelationalStorageManager()))),
    TopSort(new Program(new SemiNaiveExecutionEngine(new RelationalStorageManager()))),
    MultiJoin(new Program(new SemiNaiveExecutionEngine(new RelationalStorageManager())))
  ).map(graph =>
    graph.queries.map((hint, query) => {
      test(graph.description + "." + query.description) {
        assertEquals(
          query.relation.solve(),
          query.solution,
          hint
        )
      }
    }))
}

class SemiNaiveCollectionTransitiveClosure extends munit.FunSuite {
  List(
    Acyclic(new Program(new SemiNaiveExecutionEngine(new CollectionsStorageManager()))),
    MultiIsolatedCycle(new Program(new SemiNaiveExecutionEngine(new CollectionsStorageManager()))),
    SingleCycle(new Program(new SemiNaiveExecutionEngine(new CollectionsStorageManager()))),
    RecursivePath(new Program(new SemiNaiveExecutionEngine(new CollectionsStorageManager()))),
    TopSort(new Program(new SemiNaiveExecutionEngine(new CollectionsStorageManager()))),
    MultiJoin(new Program(new SemiNaiveExecutionEngine(new CollectionsStorageManager())))
  ).map(graph =>
    graph.queries.map((hint, query) => {
      test(graph.description + "." + query.description) {
        assertEquals(
          query.relation.solve(),
          query.solution,
          hint
        )
      }
    }))
}

class SemiNaiveIndexedCollectionTransitiveClosure extends munit.FunSuite {
  List(
    Acyclic(new Program(new SemiNaiveExecutionEngine(new IndexedCollStorageManager()))),
    MultiIsolatedCycle(new Program(new SemiNaiveExecutionEngine(new IndexedCollStorageManager()))),
    SingleCycle(new Program(new SemiNaiveExecutionEngine(new IndexedCollStorageManager()))),
    RecursivePath(new Program(new SemiNaiveExecutionEngine(new IndexedCollStorageManager()))),
    TopSort(new Program(new SemiNaiveExecutionEngine(new IndexedCollStorageManager()))),
    MultiJoin(new Program(new SemiNaiveExecutionEngine(new IndexedCollStorageManager())))
  ).map(graph =>
    graph.queries.map((hint, query) => {
      test(graph.description + "." + query.description) {
        assertEquals(
          query.relation.solve(),
          query.solution,
          hint
        )
      }
    }))
}
