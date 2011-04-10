package spath

import annotation.tailrec
import collection.mutable.{HashMap, Stack, HashSet, Set, Map}
import collection.immutable.Iterable
import collection.immutable.VectorBuilder

trait LltAlgorithm[T <: AnyRef] extends QueryExpression[T] {

  private def createGraph(e: Query): Node = Algorithm.createGraph(e)

  def evaluateWithoutCaching(e: Query): T => Iterable[T] = {
    val n = createGraph(e)

    val map = new HashMap[Node, Iterable[T]]

    o => {
      for (node <- n.next(o))
        map.put(node, List(o))
      startEvaluation
      val result = evaluate(map, List())
      endEvaluation
      result
    }
  }

  protected def startEvaluation = {}
  protected def endEvaluation = {}

  @tailrec
  private def evaluate(map: Map[Node, Iterable[T]],
                       result: Iterable[T]): Iterable[T] = {

    if (map.keys.size == 0) return result
    val newMap = new HashMap[Node, Iterable[T]]
    var newResult = new VectorBuilder[T]
    for (node <- map.keys)
      map.get(node) match {
        case Some(set) =>
          if (node.finalNode) newResult ++= set
          else {
            val children = set.flatMap(node.uniqueAxis)
            for (nextNode <- node.outgoing) {
              val newSet = children.filter(o => nextNode.isSatisfiedBy(o))
              if (newSet.size > 0) newMap.put(nextNode, newSet)
            }
          }
      }
    evaluate(newMap, result ++ newResult.result)
  }

  def evaluateWithCaching(e: Query): T => Iterable[T] = {
    val n = createGraph(e)

    val map = new HashMap[Node, Iterable[T]]

    o => {
      for (node <- n.next(o))
        map.put(node, List(o))
      startEvaluation
      val result = evaluateWithCache(map, List(), new VisitedStates)
      endEvaluation
      result
    }
  }

  private class VisitedStates {
    val map: Map[Node, Set[IdentityWrapper[T]]] = new HashMap[Node, Set[IdentityWrapper[T]]]

    def put(node: Node, objects: Iterable[T]) {
      var setOption = map.get(node)
      setOption match {
        case Some(set) => set ++= objects.map(o => IdentityWrapper(o))
        case None =>
          val set = new HashSet[IdentityWrapper[T]]
          set ++= objects.map(o => IdentityWrapper(o))
          map.put(node, set)
      }
    }

    def contains(node: Node, o: T) =
      map.get(node) match {
        case Some(set) => set.contains(IdentityWrapper(o))
        case _ => false
      }
  }

  @tailrec
  private def evaluateWithCache(map: Map[Node, Iterable[T]], result: Iterable[T], visited: VisitedStates): Iterable[T] = {

    if (map.keys.size == 0) return result
    val newMap = new HashMap[Node, Iterable[T]]
    var newResult = new VectorBuilder[T]
    for (node <- map.keys)
      map.get(node) match {
        case Some(set) =>
          if (node.finalNode) newResult ++= set
          else {
            val children = set.flatMap(node.uniqueAxis)
            for (nextNode <- node.outgoing) {
              val newSet = children.filter(o => nextNode.isSatisfiedBy(o))
              if (newSet.size > 0) {
                newMap.put(nextNode, newSet.filter(o => !visited.contains(nextNode, o)))
                visited.put(nextNode, newSet)
              }
            }
          }
      }
    evaluateWithCache(newMap, result ++ newResult.result, visited)
  }

  /**
   * Implementation of Moshe, Wolper et al.
   */
  private object Algorithm {
    var i = 0

    def newName = {
      val k = i;
      i += 1;
      k.toString
    }

    def expand(n: Node, nl: Set[Node]): Set[Node] = {
      if (n.New.size == 0) {
        var nd = n.findNode5(nl)

        if (nd != null) {
          nd.incoming ++= n.incoming
          return nl
        }
        else {
          nl += n
          if (n.next.size > 0) {
            // skip the very last state with the infinite loop.

            var newn = new Node(newName)
            newn.incoming += n
            newn.New ++= n.next
            return expand(newn, nl)
          } else
            return nl
        }
      }
      else {

        val a: Array[Query] = new Array[Query](n.New.size)
        n.New.copyToArray(a)
        val eta: Query = a(0)
        n.New -= eta

        eta match {
          case _: Predicate =>
            n.old += eta
            return expand(n, nl)
          case _: Until =>
            var n1 = new Node(newName)
            n1.New += n.new1(eta)
            n1.New --= n.old
            n1.New ++= n.New

            n1.incoming ++= n.incoming
            n1.old ++= n.old += eta
            n1.next ++= n.next
            n1.next += n.next1(eta)

            var n2 = new Node(newName)
            n2.New += n.new2(eta)
            n2.New --= n.old
            n2.New ++= n.New

            n2.incoming ++= n.incoming
            n2.old ++= n.old += eta
            n2.next ++= n.next

            return expand(n1, expand(n2, nl))

          case And(l, r) =>
            if (!n.old.contains(l))
              n.New += l
            if (!n.old.contains(r))
              n.New += r
            n.old += eta

            return expand(n, nl)

          case x: X =>
            n.old += eta
            n.next += x.next
            return expand(n, nl)
        }
      }
    }

    def createGraph(e: Query): Node = {

      val n = new Node(newName)
      val i = new Node("init")
      n.incoming += i
      n.New += e
      val ns = expand(n, new HashSet[Node])

      for (n <- ns) {
        for (i <- n.incoming)
          i.outgoing += n
        n.uniqueAxis = n.findUniqueAxis()
        n.finalNode = n.next.size == 0
      }

      markLoopNodes(ns, new HashSet[Node], 1, ns.size)
//      println("*=--=*=--=*=--=*=--=*=--=*=--=*=--=*=--=*=--=*=--=*=--=*=--=*=--=*=--=*=--=*=")
//      print(ns)
      i
    }

    private def markLoopNodes(nodes: Set[Node], visited: Set[Node], length: Int, numberOfNodes: Int) {

      for (n <- nodes) if (visited.contains(n)) n.loop = true

      if (length < numberOfNodes + 1)
        markLoopNodes(nodes.flatMap(o => o.outgoing), visited ++ nodes, length + 1, numberOfNodes)
    }

    private def print(l: Set[Node]) {
      for (n <- l)
        n.print;
      println("")
    }
  }

  private class Node(val name: String) {
    type node = Node
    type expr = Query

    val incoming: Set[node] = new HashSet[node]
    val New: Set[expr] = new HashSet[expr]
    val old: Set[expr] = new HashSet[expr]
    val next: Set[expr] = new HashSet[expr]
    val outgoing = new HashSet[node]

    var uniqueAxis: axis = null
    var finalNode: Boolean = false
    var loop: Boolean = false

    def findNode5(l: Set[node]) = {
      var node: node = null

      for (n <- l)
        if (n.old == this.old && n.next == this.next)
          node = n
      node;
    }

    def new1(e: expr) = e match {
      case u: Until => u.l
      case _ => null
    }

    def new2(e: expr) = e match {
      case u: Until => u.r
      case _ => null
    }

    def next1(e: expr) = e match {
      case u: Until => u
      case _ => null
    }

    def next(o: T) = {
      var result: Set[node] = new HashSet[node]
      nextAux(o, outgoing.toArray, 0, result)
      result
    }

    @tailrec
    private def nextAux(a: T, s: Array[node], i: Int, result: Set[node]): Unit = {
      if (i > s.size - 1) return
      if (s(i) isSatisfiedBy a) result add s(i)
      nextAux(a, s, i + 1, result)
    }

    def oldContains(e: Query): Boolean = {
      for (oldE <- old) {
        if (e eq oldE)
          return true
      }
      return false
    }

    def findUniqueAxis(): axis = {
      if (old.size == 0)
        (o: T) => new HashSet[T]

      for (e <- old) {
        e match {
          case X(f, _) => return f
          case _ =>
        }
      }

      for (e <- old) {
        e match {
          case Until(f, _, r) => if (!oldContains(r)) return f
          case _ =>
        }
      }

      (o: T) => new HashSet[T]
    }

    def print = {

      println("\t" + name + ":")
      println("\t in:")
      incoming foreach (e => println("\t\t" + e.name + "\n"))
      println("\t old:")
      old foreach (e => println("\t\t" + e))
      println("\t next:")
      next foreach (e => println("\t\t" + e))
    }

    def isSatisfiedBy(a: T): Boolean = {
      for (e <- old) {
        e match {
          case p: Predicate => if (!p.apply(a)) return false
          case _ =>
        }
      }
      return true
    }

    override def hashCode = name.hashCode

    override def equals(that: Any) = that match {
      case other: Node => other.name.equals(this.name)
    }
  }
}