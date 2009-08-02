/* Poly2Tri
 * Copyright (c) 2009, Mason Green
 * http://code.google.com/p/poly2tri/
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * * Neither the name of Poly2Tri nor the names of its contributors may be
 *   used to endorse or promote products derived from this software without specific
 *   prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.poly2tri.cdt

import scala.collection.mutable.ArrayBuffer

import shapes.{Segment, Point, Triangle}
import utils.Util

/**
 * Sweep-line, Constrained Delauney Triangulation
 * See: Domiter, V. and Žalik, B.(2008)'Sweep-line algorithm for constrained Delaunay triangulation',
 *      International Journal of Geographical Information Science,22:4,449 — 462
 */
object CDT {
  
  // Inital triangle factor
  val ALPHA = 0.3f
  val SHEER = 0.00001f
  
  // Triangulate simple polygon
  def init(points: ArrayBuffer[Point]): CDT = {
    
    var xmax, xmin = shearTransform(points.first).x
    var ymax, ymin = shearTransform(points.first).y
    
    // Calculate bounds
    for(i <- 0 until points.size) { 
      points(i) = shearTransform(points(i))
      val p = points(i)
      if(p.x > xmax) xmax = p.x
      if(p.x < xmin) xmin = p.x
      if(p.y > ymax) ymax = p.y
      if(p.y < ymin) ymin = p.y
    }
    
    val deltaX = ALPHA * (xmax - xmin)
    val deltaY = ALPHA * (ymax - ymin)
    val p1 = Point(xmin - deltaX, ymin - deltaY)
    val p2 = Point(xmax + deltaX, ymin - deltaY)
    
    val segments = initSegments(points)
    val sortedPoints = pointSort(points)
    
    val noNeighbors = new Array[Triangle](3)
    val tPoints = Array(sortedPoints(0), p1, p2)
    val iTriangle = new Triangle(tPoints, noNeighbors)
    new CDT(sortedPoints, segments, iTriangle)
  }
  
    // Create segments and connect end points; update edge event pointer
  private def initSegments(points: ArrayBuffer[Point]): List[Segment] = {
    var segments = List[Segment]()
    for(i <- 0 until points.size-1) {
      segments = new Segment(points(i), points(i+1)) :: segments
      segments.first.updateEdge
    }
    segments =  new Segment(points.first, points.last) :: segments
    segments.first.updateEdge
    segments
  }
  
  // Insertion sort is one of the fastest algorithms for sorting arrays containing 
  // fewer than ten elements, or for lists that are already mostly sorted.
  // Merge sort: O(n log n)
  private def pointSort(points: ArrayBuffer[Point]): List[Point] = {
    if(points.size < 10) 
      Util.insertSort((p1: Point, p2: Point) => p1 > p2)(points).toList
    else
      Util.msort((p1: Point, p2: Point) => p1 > p2)(points.toList)
  }
  
  // Prevents any two distinct endpoints from lying on a common horizontal line, and avoiding
  // the degenerate case. See Mark de Berg et al, Chapter 6.3
  private def shearTransform(point: Point) = Point(point.x, point.y + point.x * SHEER)
  
}

class CDT(val points: List[Point], val segments: List[Segment], iTriangle: Triangle) {
  
  // Triangle list
  def triangles = mesh.map
  // The triangle mesh
  private val mesh = new Mesh(iTriangle)
  // Advancing front
  private val aFront = new AFront(iTriangle)
  
  private val PI_2 = Math.Pi/2
  
  // Sweep points; build mesh
  sweep
  // Finalize triangulation
  finalization
  
  // Implement sweep-line 
  private def sweep {
    for(i <- 1 until 9 /*points.size*/) {
      val point = points(i)
      println(point)
      // Process Point event
      val triangle = pointEvent(point)
      // Process edge events
     point.edges.foreach(e => edgeEvent(e, triangle))
    }
  }  
  
  // Point event
  private def pointEvent(point: Point): Triangle = {
    
    val node = aFront.locate(point)
    // Neightbor points (ccw & cw) and triangle(i)
    val cwPoint = node.next.point
    val ccwPoint = node.point
    val nTri = node.triangle
    
    val pts = Array(point, ccwPoint,  cwPoint)
    val neighbors = Array(nTri, null, null)
    val triangle = new Triangle(pts, neighbors)
    mesh.map += triangle
    
    // Check if edges need to be swapped to preserve CDT
    // TODO: Make sure AFront pointers are updated correctly
    val oPoint = nTri oppositePoint triangle
    if(illegal(ccwPoint, oPoint, cwPoint, point)) {
      legalization(triangle, nTri, oPoint)
    }
    
    nTri.updateNeighbors(ccwPoint, cwPoint, triangle)
    
    // Update advancing front
    val newNode = aFront.insert(point, triangle, node)
    // Fill in adjacent triangles if required
    scan(newNode)
    triangle
  }
  
  // EdgeEvent
  private def edgeEvent(edge: Segment, triangle: Triangle) { 
    // STEP 1: Locate the first intersected triangle
    val first = triangle.locateFirst(edge)
    println(first)
    if(first != null && first != triangle && !first.contains(edge)) 
      mesh.map -= first
    // STEP 2: Remove intersected triangles
    // STEP 3: Triangulate empty areas.
  }
  
  // Scan left and right along AFront to fill holes
  def scan(n: Node) {
    
    var node = n.next
    // Update right
    if(node.next != null) {
      var angle = 0.0
      do {
        angle = fill(node)
        node = node.next
      } while(angle <= PI_2 && node.next != null) 
    }
    
    node = n.prev
    // Update left
    if(node.prev != null) {
      var angle = 0.0
      do {
	    angle = fill(node)
        node = node.prev
      } while(angle <= PI_2 && node.prev != null)
    }
  }
  
  // Fill empty space with a triangle
  def fill(node: Node): Double = {
	  val a = (node.prev.point - node.point)
	  val b = (node.next.point - node.point)
	  val angle = Math.abs(Math.atan2(a cross b, a dot b))
	  if(angle <= PI_2) {
	    val points = Array(node.prev.point, node.next.point, node.point)
	    val neighbors = Array(node.prev.triangle, null, node.triangle)
	    val triangle = new Triangle(points, neighbors)
        // Update neighbor pointers
        node.prev.triangle.updateNeighbors(triangle.points(1), triangle.points(2), triangle)
        node.triangle.updateNeighbors(triangle.points(1), triangle.points(2), triangle)
	    mesh.map += triangle
	    aFront -= (node.prev, node, triangle)
	  }
      angle
  }
  
  // Do edges need to be swapped?
  private def illegal(p1: Point, p2: Point, p3: Point, p4:Point): Boolean = {
	  val v1 = p3 - p2
      val v2 = p1 - p2
      val v3 = p1 - p4
      val v4 = p3 - p4
      if((v1 dot v2) < 0 && (v3 dot v4) < 0)
        // swap the edge
        true 
      else 
        false
  }
  
  // Flip edges and rotate everything clockwise
  private def legalization(t1: Triangle, t2: Triangle, oPoint: Point) {
    // Rotate points
    val point = t1.points(0)
    t1.points(1) = t1.points(0)
    t1.points(0) = t1.points(2)
    t1.points(2) = oPoint    
    val tmp = t2.points(1)
    t2.points(1) = point
    t2.points(0) = t2.points(2)
    t2.points(2) = tmp
    // Rotate neighbors
    
  }
 
  private def finalization {
  }
  
}
