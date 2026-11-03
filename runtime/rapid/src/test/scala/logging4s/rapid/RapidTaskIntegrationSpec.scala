package logging4s.rapid

import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.matchers.should.Matchers

import rapid.{AsyncTaskSpec, Task}

import logging4s.core.{Delay, Logging}

import logging4s.rapid.RapidInstances.given
import logging4s.logback.LogbackInstances.given

class RapidTaskIntegrationSpec extends AsyncWordSpec, AsyncTaskSpec, Matchers:

  "Rapid integration" must:
    "use given instance with Sync implementation for Delay" in:
      val expected = "test_value"
      Delay[Task].delay(expected).map(_ shouldEqual expected)

    "not run the thunk until the task runs" in:
      var evaluations = 0

      val task = Delay[Task].delay {
        evaluations += 1
        evaluations
      }

      evaluations shouldEqual 0
      task.map(result => (result, evaluations) shouldEqual (1, 1))

    "give a unit that completes with ()" in:
      Delay[Task].unit.map(_ shouldEqual ())

    "right create logging instance for IO monad" in:
      for _ <- Logging.create[Task]("test")
      yield assert(true)
