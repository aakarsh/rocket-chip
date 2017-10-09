// See LICENSE.SiFive for license details.

package freechips.rocketchip.diplomacy

import Chisel._
import chisel3.internal.sourceinfo.SourceInfo
import freechips.rocketchip.config.{Parameters,Field}
import freechips.rocketchip.util.HeterogeneousBag
import scala.collection.mutable.ListBuffer
import scala.util.matching._

object CardinalityInferenceDirection {
  val cases = Seq(SOURCE_TO_SINK, SINK_TO_SOURCE, NO_INFERENCE)
  sealed trait T {
    def flip = this match {
      case SOURCE_TO_SINK => SINK_TO_SOURCE
      case SINK_TO_SOURCE => SOURCE_TO_SINK
      case NO_INFERENCE   => NO_INFERENCE
    }
  }

  case object SOURCE_TO_SINK extends T
  case object SINK_TO_SOURCE extends T
  case object NO_INFERENCE   extends T
}

private case object CardinalityInferenceDirectionKey extends
  Field[CardinalityInferenceDirection.T](CardinalityInferenceDirection.NO_INFERENCE)

case object MonitorsEnabled extends Field[Boolean](true)
case object RenderFlipped extends Field[Boolean](false)

case class RenderedEdge(
  colour:  String,
  label:   String  = "",
  flipped: Boolean = false) // prefer to draw the arrow pointing the opposite direction of other edges

// DI = Downwards flowing Parameters received on the inner side of the node
// UI = Upwards   flowing Parameters generated by the inner side of the node
// EI = Edge Parameters describing a connection on the inner side of the node
// BI = Bundle type used when connecting to the inner side of the node
trait InwardNodeImp[DI, UI, EI, BI <: Data]
{
  def edgeI(pd: DI, pu: UI, p: Parameters, sourceInfo: SourceInfo): EI
  def bundleI(ei: EI): BI

  // Edge functions
  def monitor(bundle: BI, edge: EI) {}
  def render(e: EI): RenderedEdge

  // optional methods to track node graph
  def mixI(pu: UI, node: InwardNode[DI, UI, BI]): UI = pu // insert node into parameters
  def getO(pu: UI): Option[BaseNode] = None // most-outward common node
}

// DO = Downwards flowing Parameters generated by the outer side of the node
// UO = Upwards   flowing Parameters received on the outer side of the node
// EO = Edge Parameters describing a connection on the outer side of the node
// BO = Bundle type used when connecting to the outer side of the node
trait OutwardNodeImp[DO, UO, EO, BO <: Data]
{
  def edgeO(pd: DO, pu: UO, p: Parameters, sourceInfo: SourceInfo): EO
  def bundleO(eo: EO): BO

  // optional methods to track node graph
  def mixO(pd: DO, node: OutwardNode[DO, UO, BO]): DO = pd // insert node into parameters
  def getI(pd: DO): Option[BaseNode] = None // most-inward common node
}

abstract class NodeImp[D, U, EO, EI, B <: Data]
  extends Object with InwardNodeImp[D, U, EI, B] with OutwardNodeImp[D, U, EO, B]

// If your edges have the same direction, using this saves you some typing
abstract class SimpleNodeImp[D, U, E, B <: Data]
  extends NodeImp[D, U, E, E, B]
{
  def edge(pd: D, pu: U, p: Parameters, sourceInfo: SourceInfo): E
  def edgeO(pd: D, pu: U, p: Parameters, sourceInfo: SourceInfo) = edge(pd, pu, p, sourceInfo)
  def edgeI(pd: D, pu: U, p: Parameters, sourceInfo: SourceInfo) = edge(pd, pu, p, sourceInfo)
  def bundle(e: E): B
  def bundleO(e: E) = bundle(e)
  def bundleI(e: E) = bundle(e)
}

abstract class BaseNode(implicit val valName: ValName)
{
  require (LazyModule.scope.isDefined, "You cannot create a node outside a LazyModule!")

  val lazyModule = LazyModule.scope.get
  val index = lazyModule.nodes.size
  lazyModule.nodes = this :: lazyModule.nodes

  val serial = BaseNode.serial
  BaseNode.serial = BaseNode.serial + 1
  protected[diplomacy] def instantiate(): Seq[Dangle]

  def name = lazyModule.name + "." + valName.name
  def omitGraphML = outputs.isEmpty && inputs.isEmpty
  lazy val nodedebugstring: String = ""

  def wirePrefix = {
    val camelCase = "([a-z])([A-Z])".r
    val decamel = camelCase.replaceAllIn(valName.name, _ match { case camelCase(l, h) => l + "_" + h })
    val trimNode = "_?node$".r
    val name = trimNode.replaceFirstIn(decamel.toLowerCase, "")
    if (name.isEmpty) "" else name + "_"
  }

  protected[diplomacy] def gci: Option[BaseNode] // greatest common inner
  protected[diplomacy] def gco: Option[BaseNode] // greatest common outer
  protected[diplomacy] def inputs:  Seq[(BaseNode, RenderedEdge)]
  protected[diplomacy] def outputs: Seq[(BaseNode, RenderedEdge)]
}

object BaseNode
{
  protected[diplomacy] var serial = 0
}

// !!! rename the nodes we bind?
case class NodeHandle[DI, UI, BI <: Data, DO, UO, BO <: Data]
  (inwardHandle: InwardNodeHandle[DI, UI, BI], outwardHandle: OutwardNodeHandle[DO, UO, BO])
  extends Object with InwardNodeHandle[DI, UI, BI] with OutwardNodeHandle[DO, UO, BO]
{
  val inward = inwardHandle.inward
  val outward = outwardHandle.outward
}

trait InwardNodeHandle[DI, UI, BI <: Data]
{
  protected[diplomacy] val inward: InwardNode[DI, UI, BI]
  def := (h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo) { inward.:=(h)(p, sourceInfo) }
  def :*= (h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo) { inward.:*=(h)(p, sourceInfo) }
  def :=* (h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo) { inward.:=*(h)(p, sourceInfo) }
  def :=? (h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo) { inward.:=?(h)(p, sourceInfo) }
}

sealed trait NodeBinding
case object BIND_ONCE  extends NodeBinding
case object BIND_QUERY extends NodeBinding
case object BIND_STAR  extends NodeBinding

trait InwardNode[DI, UI, BI <: Data] extends BaseNode with InwardNodeHandle[DI, UI, BI]
{
  protected[diplomacy] val inward = this

  protected[diplomacy] val numPI: Range.Inclusive
  require (!numPI.isEmpty, s"No number of inputs would be acceptable to ${name}${lazyModule.line}")
  require (numPI.start >= 0, s"${name} accepts a negative number of inputs${lazyModule.line}")

  private val accPI = ListBuffer[(Int, OutwardNode[DI, UI, BI], NodeBinding, Parameters, SourceInfo)]()
  private var iRealized = false

  protected[diplomacy] def iPushed = accPI.size
  protected[diplomacy] def iPush(index: Int, node: OutwardNode[DI, UI, BI], binding: NodeBinding)(implicit p: Parameters, sourceInfo: SourceInfo) {
    val info = sourceLine(sourceInfo, " at ", "")
    val noIs = numPI.size == 1 && numPI.contains(0)
    require (!noIs, s"${name}${lazyModule.line} was incorrectly connected as a sink" + info)
    require (!iRealized, s"${name}${lazyModule.line} was incorrectly connected as a sink after its .module was used" + info)
    accPI += ((index, node, binding, p, sourceInfo))
  }

  protected[diplomacy] lazy val iBindings = { iRealized = true; accPI.result() }

  protected[diplomacy] val iStar: Int
  protected[diplomacy] val iPortMapping: Seq[(Int, Int)]
  protected[diplomacy] val iParams: Seq[UI]
}

trait OutwardNodeHandle[DO, UO, BO <: Data]
{
  protected[diplomacy] val outward: OutwardNode[DO, UO, BO]
}

trait OutwardNode[DO, UO, BO <: Data] extends BaseNode with OutwardNodeHandle[DO, UO, BO]
{
  protected[diplomacy] val outward = this

  protected[diplomacy] val numPO: Range.Inclusive
  require (!numPO.isEmpty, s"No number of outputs would be acceptable to ${name}${lazyModule.line}")
  require (numPO.start >= 0, s"${name} accepts a negative number of outputs${lazyModule.line}")

  private val accPO = ListBuffer[(Int, InwardNode [DO, UO, BO], NodeBinding, Parameters, SourceInfo)]()
  private var oRealized = false

  protected[diplomacy] def oPushed = accPO.size
  protected[diplomacy] def oPush(index: Int, node: InwardNode [DO, UO, BO], binding: NodeBinding)(implicit p: Parameters, sourceInfo: SourceInfo) {
    val info = sourceLine(sourceInfo, " at ", "")
    val noOs = numPO.size == 1 && numPO.contains(0)
    require (!noOs, s"${name}${lazyModule.line} was incorrectly connected as a source" + info)
    require (!oRealized, s"${name}${lazyModule.line} was incorrectly connected as a source after its .module was used" + info)
    accPO += ((index, node, binding, p, sourceInfo))
  }

  protected[diplomacy] lazy val oBindings = { oRealized = true; accPO.result() }

  protected[diplomacy] val oStar: Int
  protected[diplomacy] val oPortMapping: Seq[(Int, Int)]
  protected[diplomacy] val oParams: Seq[DO]
}

abstract class CycleException(kind: String, loop: Seq[String]) extends Exception(s"Diplomatic ${kind} cycle detected involving ${loop}")
case class StarCycleException(loop: Seq[String] = Nil) extends CycleException("star", loop)
case class DownwardCycleException(loop: Seq[String] = Nil) extends CycleException("downward", loop)
case class UpwardCycleException(loop: Seq[String] = Nil) extends CycleException("upward", loop)

case class Edges[EI, EO](in: EI, out: EO)
sealed abstract class MixedNode[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data](
  inner: InwardNodeImp [DI, UI, EI, BI],
  outer: OutwardNodeImp[DO, UO, EO, BO])(
  protected[diplomacy] val numPO: Range.Inclusive,
  protected[diplomacy] val numPI: Range.Inclusive)(
  implicit valName: ValName)
  extends BaseNode with InwardNode[DI, UI, BI] with OutwardNode[DO, UO, BO]
{
  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStar: Int, oStar: Int): (Int, Int)
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[DI]): Seq[DO]
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[UO]): Seq[UI]

  private var starCycleGuard = false
  protected[diplomacy] lazy val (oPortMapping, iPortMapping, oStar, iStar) = {
    try {
      if (starCycleGuard) throw StarCycleException()
      val oStars = oBindings.filter { case (_,_,b,_,_) => b == BIND_STAR }.size
      val iStars = iBindings.filter { case (_,_,b,_,_) => b == BIND_STAR }.size
      val oKnown = oBindings.map { case (_, n, b, _, _) => b match {
        case BIND_ONCE  => 1
        case BIND_QUERY => n.iStar
        case BIND_STAR  => 0 }}.foldLeft(0)(_+_)
      val iKnown = iBindings.map { case (_, n, b, _, _) => b match {
        case BIND_ONCE  => 1
        case BIND_QUERY => n.oStar
        case BIND_STAR  => 0 }}.foldLeft(0)(_+_)
      val (iStar, oStar) = resolveStar(iKnown, oKnown, iStars, oStars)
      val oSum = oBindings.map { case (_, n, b, _, _) => b match {
        case BIND_ONCE  => 1
        case BIND_QUERY => n.iStar
        case BIND_STAR  => oStar }}.scanLeft(0)(_+_)
      val iSum = iBindings.map { case (_, n, b, _, _) => b match {
        case BIND_ONCE  => 1
        case BIND_QUERY => n.oStar
        case BIND_STAR  => iStar }}.scanLeft(0)(_+_)
      val oTotal = oSum.lastOption.getOrElse(0)
      val iTotal = iSum.lastOption.getOrElse(0)
      require(numPO.contains(oTotal), s"${name} has ${oTotal} outputs, expected ${numPO}${lazyModule.line}")
      require(numPI.contains(iTotal), s"${name} has ${iTotal} inputs, expected ${numPI}${lazyModule.line}")
      (oSum.init zip oSum.tail, iSum.init zip iSum.tail, oStar, iStar)
    } catch {
      case c: StarCycleException => throw c.copy(loop = s"${name}${lazyModule.line}" +: c.loop)
    }
  }

  lazy val oPorts = oBindings.flatMap { case (i, n, _, p, s) =>
    val (start, end) = n.iPortMapping(i)
    (start until end) map { j => (j, n, p, s) }
  }
  lazy val iPorts = iBindings.flatMap { case (i, n, _, p, s) =>
    val (start, end) = n.oPortMapping(i)
    (start until end) map { j => (j, n, p, s) }
  }

  private var oParamsCycleGuard = false
  protected[diplomacy] lazy val oParams: Seq[DO] = {
    try {
      if (oParamsCycleGuard) throw DownwardCycleException()
      oParamsCycleGuard = true
      val o = mapParamsD(oPorts.size, iPorts.map { case (i, n, _, _) => n.oParams(i) })
      require (o.size == oPorts.size, s"Bug in diplomacy; ${name} has ${o.size} != ${oPorts.size} down/up outer parameters${lazyModule.line}")
      o.map(outer.mixO(_, this))
    } catch {
      case c: DownwardCycleException => throw c.copy(loop = s"${name}${lazyModule.line}" +: c.loop)
    }
  }

  private var iParamsCycleGuard = false
  protected[diplomacy] lazy val iParams: Seq[UI] = {
    try {
      if (iParamsCycleGuard) throw UpwardCycleException()
      iParamsCycleGuard = true
      val i = mapParamsU(iPorts.size, oPorts.map { case (o, n, _, _) => n.iParams(o) })
      require (i.size == iPorts.size, s"Bug in diplomacy; ${name} has ${i.size} != ${iPorts.size} up/down inner parameters${lazyModule.line}")
      i.map(inner.mixI(_, this))
    } catch {
      case c: UpwardCycleException => throw c.copy(loop = s"${name}${lazyModule.line}" +: c.loop)
    }
  }

  protected[diplomacy] def gco = if (iParams.size != 1) None else inner.getO(iParams(0))
  protected[diplomacy] def gci = if (oParams.size != 1) None else outer.getI(oParams(0))

  protected[diplomacy] lazy val edgesOut = (oPorts zip oParams).map { case ((i, n, p, s), o) => outer.edgeO(o, n.iParams(i), p, s) }
  protected[diplomacy] lazy val edgesIn  = (iPorts zip iParams).map { case ((o, n, p, s), i) => inner.edgeI(n.oParams(o), i, p, s) }

  // If you need access to the edges of a foreign Node, use this method (in/out create bundles)
  lazy val edges = Edges(edgesIn, edgesOut)

  protected[diplomacy] lazy val bundleOut: Seq[BO] = edgesOut.map(e => Wire(outer.bundleO(e)))
  protected[diplomacy] lazy val bundleIn:  Seq[BI] = edgesIn .map(e => Wire(inner.bundleI(e)))

  protected[diplomacy] def danglesOut: Seq[Dangle] = oPorts.zipWithIndex.map { case ((j, n, _, _), i) =>
    Dangle(
      source = HalfEdge(serial, i),
      sink   = HalfEdge(n.serial, j),
      flipped= false,
      name   = wirePrefix + "out",
      data   = bundleOut(i))
  }
  protected[diplomacy] def danglesIn: Seq[Dangle] = iPorts.zipWithIndex.map { case ((j, n, _, _), i) =>
    Dangle(
      source = HalfEdge(n.serial, j),
      sink   = HalfEdge(serial, i),
      flipped= true,
      name   = wirePrefix + "in",
      data   = bundleIn(i))
  }

  private var bundlesSafeNow = false
  // Accessors to the result of negotiation to be used in LazyModuleImp:
  def out: Seq[(BO, EO)] = {
    require(bundlesSafeNow, s"${name}.out should only be called from the context of its module implementation")
    bundleOut zip edgesOut
  }
  def in: Seq[(BI, EI)] = {
    require(bundlesSafeNow, s"${name}.in should only be called from the context of its module implementation")
    bundleIn zip edgesIn
  }

  // Used by LazyModules.module.instantiate
  protected val identity = false
  protected[diplomacy] def instantiate() = {
    bundlesSafeNow = true
    if (!identity) {
      (iPorts zip in) foreach {
        case ((_, _, p, _), (b, e)) => if (p(MonitorsEnabled)) inner.monitor(b, e)
    } }
    danglesOut ++ danglesIn
  }

  // connects the outward part of a node with the inward part of this node
  private def bind(h: OutwardNodeHandle[DI, UI, BI], binding: NodeBinding)(implicit p: Parameters, sourceInfo: SourceInfo) {
    val x = this // x := y
    val y = h.outward
    val info = sourceLine(sourceInfo, " at ", "")
    val i = x.iPushed
    val o = y.oPushed
    y.oPush(i, x, binding match {
      case BIND_ONCE  => BIND_ONCE
      case BIND_STAR  => BIND_QUERY
      case BIND_QUERY => BIND_STAR })
    x.iPush(o, y, binding)
  }

  override def :=  (h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo) = bind(h, BIND_ONCE)
  override def :*= (h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo) = bind(h, BIND_STAR)
  override def :=* (h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo) = bind(h, BIND_QUERY)
  override def :=? (h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo) = {
    p(CardinalityInferenceDirectionKey) match {
      case CardinalityInferenceDirection.SOURCE_TO_SINK => this :=* h
      case CardinalityInferenceDirection.SINK_TO_SOURCE => this :*= h
      case CardinalityInferenceDirection.NO_INFERENCE   => this :=  h
    }
  }

  // meta-data for printing the node graph
  protected[diplomacy] def inputs = (iPorts zip edgesIn) map { case ((_, n, p, _), e) =>
    val re = inner.render(e)
    (n, re.copy(flipped = re.flipped != p(RenderFlipped)))
  }
  protected[diplomacy] def outputs = oPorts map { case (i, n, _, _) => (n, n.inputs(i)._2) }
}

abstract class MixedCustomNode[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data](
  inner: InwardNodeImp [DI, UI, EI, BI],
  outer: OutwardNodeImp[DO, UO, EO, BO])(
  numPO: Range.Inclusive,
  numPI: Range.Inclusive)(
  implicit valName: ValName)
  extends MixedNode(inner, outer)(numPO, numPI)
{
  def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int)
  def mapParamsD(n: Int, p: Seq[DI]): Seq[DO]
  def mapParamsU(n: Int, p: Seq[UO]): Seq[UI]
}

abstract class CustomNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(
  numPO: Range.Inclusive,
  numPI: Range.Inclusive)(
  implicit valName: ValName)
  extends MixedCustomNode(imp, imp)(numPO, numPI)

class MixedAdapterNode[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data](
  inner: InwardNodeImp [DI, UI, EI, BI],
  outer: OutwardNodeImp[DO, UO, EO, BO])(
  dFn: DI => DO,
  uFn: UO => UI,
  num: Range.Inclusive = 0 to 999)(
  implicit valName: ValName)
  extends MixedNode(inner, outer)(num, num)
{
  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int) = {
    require (oStars + iStars <= 1, s"${name} (an adapter) appears left of a :*= ${iStars} times and right of a :=* ${oStars} times; at most once is allowed${lazyModule.line}")
    if (oStars > 0) {
      require (iKnown >= oKnown, s"${name} (an adapter) has ${oKnown} outputs and ${iKnown} inputs; cannot assign ${iKnown-oKnown} edges to resolve :=*${lazyModule.line}")
      (0, iKnown - oKnown)
    } else {
      require (oKnown >= iKnown, s"${name} (an adapter) has ${oKnown} outputs and ${iKnown} inputs; cannot assign ${oKnown-iKnown} edges to resolve :*=${lazyModule.line}")
      (oKnown - iKnown, 0)
    }
  }
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[DI]): Seq[DO] = {
    require(n == p.size, s"${name} has ${p.size} inputs and ${n} outputs; they must match${lazyModule.line}")
    p.map(dFn)
  }
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[UO]): Seq[UI] = {
    require(n == p.size, s"${name} has ${n} inputs and ${p.size} outputs; they must match${lazyModule.line}")
    p.map(uFn)
  }
}

class AdapterNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(
  dFn: D => D,
  uFn: U => U,
  num: Range.Inclusive = 0 to 999)(
  implicit valName: ValName)
    extends MixedAdapterNode[D, U, EI, B, D, U, EO, B](imp, imp)(dFn, uFn, num)

// IdentityNodes automatically connect their inputs to outputs
class IdentityNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])()(implicit valName: ValName)
  extends AdapterNode(imp)({ s => s }, { s => s })
{
  protected override val identity = true
  override protected[diplomacy] def instantiate() = {
    val dangles = super.instantiate()
    (out zip in) map { case ((o, _), (i, _)) => o <> i }
    dangles
  } 
}

class MixedNexusNode[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data](
  inner: InwardNodeImp [DI, UI, EI, BI],
  outer: OutwardNodeImp[DO, UO, EO, BO])(
  dFn: Seq[DI] => DO,
  uFn: Seq[UO] => UI,
  numPO: Range.Inclusive = 1 to 999,
  numPI: Range.Inclusive = 1 to 999)(
  implicit valName: ValName)
  extends MixedNode(inner, outer)(numPO, numPI)
{
//  require (numPO.end >= 1, s"${name} does not accept outputs${lazyModule.line}")
//  require (numPI.end >= 1, s"${name} does not accept inputs${lazyModule.line}")

  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int) = {
    require (iStars == 0, s"${name} (a nexus) appears left of :*= (perhaps you should flip the '*' to :=*?)${lazyModule.line}")
    require (oStars == 0, s"${name} (a nexus) appears right of a :=* (perhaps you should flip the '*' to :*=?)${lazyModule.line}")
    (0, 0)
  }
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[DI]): Seq[DO] = { val a = dFn(p); Seq.fill(n)(a) }
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[UO]): Seq[UI] = { val a = uFn(p); Seq.fill(n)(a) }
}

class NexusNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(
  dFn: Seq[D] => D,
  uFn: Seq[U] => U,
  numPO: Range.Inclusive = 1 to 999,
  numPI: Range.Inclusive = 1 to 999)(
  implicit valName: ValName)
    extends MixedNexusNode[D, U, EI, B, D, U, EO, B](imp, imp)(dFn, uFn, numPO, numPI)

// There are no Mixed SourceNodes
class SourceNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(po: Seq[D])(implicit valName: ValName)
  extends MixedNode(imp, imp)(po.size to po.size, 0 to 0)
{
  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int) = {
    require (oStars <= 1, s"${name} (a source) appears right of a :=* ${oStars} times; at most once is allowed${lazyModule.line}")
    require (iStars == 0, s"${name} (a source) cannot appear left of a :*=${lazyModule.line}")
    require (iKnown == 0, s"${name} (a source) cannot appear left of a :=${lazyModule.line}")
    require (po.size >= oKnown, s"${name} (a source) has ${oKnown} outputs out of ${po.size}; cannot assign ${po.size - oKnown} edges to resolve :=*${lazyModule.line}")
    (0, po.size - oKnown)
  }
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[D]): Seq[D] = po
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[U]): Seq[U] = Seq()
}

// There are no Mixed SinkNodes
class SinkNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(pi: Seq[U])(implicit valName: ValName)
  extends MixedNode(imp, imp)(0 to 0, pi.size to pi.size)
{
  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int) = {
    require (iStars <= 1, s"${name} (a sink) appears left of a :*= ${iStars} times; at most once is allowed${lazyModule.line}")
    require (oStars == 0, s"${name} (a sink) cannot appear right of a :=*${lazyModule.line}")
    require (oKnown == 0, s"${name} (a sink) cannot appear right of a :=${lazyModule.line}")
    require (pi.size >= iKnown, s"${name} (a sink) has ${iKnown} inputs out of ${pi.size}; cannot assign ${pi.size - iKnown} edges to resolve :*=${lazyModule.line}")
    (pi.size - iKnown, 0)
  }
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[D]): Seq[D] = Seq()
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[U]): Seq[U] = pi
}
