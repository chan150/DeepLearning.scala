package com.thoughtworks.deeplearning

import com.thoughtworks.raii._
import com.thoughtworks.raii.ResourceFactoryT.ResourceT

import scalaz.{-\/, @@, Applicative, Monoid, Semigroup, \/, \/-}
import scalaz.concurrent.{Future, Task}
import com.thoughtworks.raii.EitherTNondeterminism.eitherTParallelApplicative
import com.thoughtworks.raii.Shared._
import scala.language.existentials
import Future._
import scala.util.control.NoStackTrace
import scalaz.Tags.Parallel
import scalaz.syntax.all._

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object TapeTaskFactory {

  @inline
  def binary[Data0, Delta0, Data1, Delta1, OutputData, OutputDelta](operand0: RAIITask[_ <: Tape.Aux[Data0, Delta0]],
                                                                    operand1: RAIITask[_ <: Tape.Aux[Data1, Delta1]])(
      computeForward: (Data0,
                       Data1) => Task[(OutputData, OutputDelta => (RAIITask[_ <: Delta0], RAIITask[_ <: Delta1]))])(
      implicit binaryTapeTaskFactory: BinaryTapeTaskFactory[OutputData, OutputDelta])
    : RAIITask[Tape.Aux[OutputData, OutputDelta]] = {
    binaryTapeTaskFactory(operand0, operand1)(computeForward)
  }

  @inline
  def unary[Data, Delta, OutputData, OutputDelta](operand: RAIITask[_ <: Tape.Aux[Data, Delta]])(
      computeForward: (Data) => Task[(OutputData, OutputDelta => RAIITask[_ <: Delta])])(
      implicit unaryTapeTaskFactory: UnaryTapeTaskFactory[OutputData, OutputDelta])
    : RAIITask[Tape.Aux[OutputData, OutputDelta]] = {
    unaryTapeTaskFactory(operand)(computeForward)
  }

  private abstract class Output[OutputData, OutputDelta: Monoid](override val data: OutputData)
      extends Tape
      with ResourceT[Future, Throwable \/ Tape.Aux[OutputData, OutputDelta]] {

    override final type Delta = OutputDelta
    override final type Data = OutputData
    final override def value: \/-[this.type] = \/-(this)

    @volatile
    protected var deltaAccumulator: OutputDelta = mzero[OutputDelta]

    final override def backward[CovariantDelta <: OutputDelta](deltaFuture: RAIITask[CovariantDelta]): Future[Unit] = {
      logged(deltaFuture.map { delta =>
        synchronized {
          deltaAccumulator |+|= delta
        }
      })
    }

  }

  trait BinaryTapeTaskFactory[OutputData, OutputDelta] {

    def apply[Data0, Delta0, Data1, Delta1](operand0: RAIITask[_ <: Tape.Aux[Data0, Delta0]],
                                            operand1: RAIITask[_ <: Tape.Aux[Data1, Delta1]])(
        computeForward: (Data0,
                         Data1) => Task[(OutputData, OutputDelta => (RAIITask[_ <: Delta0], RAIITask[_ <: Delta1]))])
      : RAIITask[Tape.Aux[OutputData, OutputDelta]]

  }

  trait UnaryTapeTaskFactory[OutputData, OutputDelta] {

    def apply[Data, Delta](operand: RAIITask[_ <: Tape.Aux[Data, Delta]])(
        computeForward: (Data) => Task[(OutputData, OutputDelta => RAIITask[_ <: Delta])])
      : RAIITask[Tape.Aux[OutputData, OutputDelta]]
  }

  object BinaryTapeTaskFactory {

    /** An exception that contains multiple Throwables. */
    final case class MultipleException(throwableSet: Set[Throwable])
        extends Exception("Multiple exceptions found")
        with NoStackTrace {
      override def toString: String = throwableSet.toString()
    }

    implicit def throwableSemigroup = new Semigroup[Throwable] {
      override def append(f1: Throwable, f2: => Throwable): Throwable =
        f1 match {
          case MultipleException(exceptionSet1) =>
            f2 match {
              case MultipleException(exceptionSet2) => MultipleException(exceptionSet1 ++ exceptionSet2)
              case _: Throwable => MultipleException(exceptionSet1 + f2)
            }
          case _: Throwable =>
            f2 match {
              case MultipleException(exceptionSet2) => MultipleException(exceptionSet2 + f1)
              case _: Throwable => MultipleException(Set(f1, f2))
            }
        }
    }

    final class MonoidBinaryTapeTaskFactory[OutputData, OutputDelta: Monoid]
        extends BinaryTapeTaskFactory[OutputData, OutputDelta] {
      @inline
      override def apply[Data0, Delta0, Data1, Delta1](operand0: RAIITask[_ <: Tape.Aux[Data0, Delta0]],
                                                       operand1: RAIITask[_ <: Tape.Aux[Data1, Delta1]])(
          computeForward: (Data0,
                           Data1) => Task[(OutputData, OutputDelta => (RAIITask[_ <: Delta0], RAIITask[_ <: Delta1]))])
        : RAIITask[Tape.Aux[OutputData, OutputDelta]] = {

        import com.thoughtworks.raii.ResourceFactoryT.resourceFactoryTParallelApplicative

        val parallelTuple =
          Applicative[Lambda[x => RAIITask[x] @@ Parallel]]
            .tuple2(Parallel(operand0), Parallel(operand1))

        val tuple = Parallel.unwrap(parallelTuple)

        tuple.flatMap { pair =>
          val (upstream0, upstream1) = pair
          val resource: RAIIFuture[Throwable \/ Tape.Aux[OutputData, OutputDelta]] = { () =>
            computeForward(upstream0.data, upstream1.data).get.map {
              case left @ -\/(_) =>
                ResourceT.unmanaged(left)
              case right @ \/-((forwardData, computeBackward)) =>
                new Output[OutputData, OutputDelta](forwardData) {
                  override def release(): Future[Unit] = {
                    val (upstream0DeltaFuture, upstream1DeltaFuture) = computeBackward(deltaAccumulator)
                    Parallel.unwrap {
                      Future.futureParallelApplicativeInstance.apply2(
                        Parallel(upstream0.backward(upstream0DeltaFuture)),
                        Parallel(upstream1.backward(upstream1DeltaFuture))
                      ) { (_: Unit, _: Unit) =>
                        ()
                      }
                    }
                  }
                }
            }
          }
          new RAIITask(resource.shared)
        }
      }
    }

    @inline
    implicit def monoidBinaryTapeTaskFactory[OutputData, OutputDelta: Monoid]
      : BinaryTapeTaskFactory[OutputData, OutputDelta] = {
      new MonoidBinaryTapeTaskFactory[OutputData, OutputDelta]
    }
  }

  object UnaryTapeTaskFactory {
    final class MonoidUnaryTapeTaskFactory[OutputData, OutputDelta: Monoid]
        extends UnaryTapeTaskFactory[OutputData, OutputDelta] {
      @inline
      override def apply[Data, Delta](operand: RAIITask[_ <: Tape.Aux[Data, Delta]])(
          computeForward: Data => Task[(OutputData, OutputDelta => RAIITask[_ <: Delta])])
        : RAIITask[Tape.Aux[OutputData, OutputDelta]] = {
        operand.flatMap {
          case (upstream) =>
            val resource: RAIIFuture[Throwable \/ Tape.Aux[OutputData, OutputDelta]] = { () =>
              computeForward(upstream.data).get.map {
                case left @ -\/(_) =>
                  ResourceT.unmanaged(left)
                case right @ \/-((forwardData, computeBackward)) =>
                  upstream.workaround10251 match {
                    case trainable: Tape =>
                      new Output[OutputData, OutputDelta](forwardData) {
                        override def release(): Future[Unit] = {
                          trainable.backward(computeBackward(deltaAccumulator))
                        }
                      }
                  }
              }
            }
            new RAIITask(resource.shared)
        }
      }
    }

    @inline
    implicit def monoidUnaryTapeTaskFactory[OutputData, OutputDelta: Monoid]
      : UnaryTapeTaskFactory[OutputData, OutputDelta] = {
      new MonoidUnaryTapeTaskFactory[OutputData, OutputDelta]
    }
  }

}
