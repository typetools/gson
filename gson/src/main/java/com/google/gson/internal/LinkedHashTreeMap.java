/*
 * Copyright (C) 2010 The Android Open Source Project
 * Copyright (C) 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.gson.internal;

import java.io.ObjectStreamException;
import java.io.Serializable;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.KeyFor;
import org.checkerframework.checker.nullness.qual.EnsuresKeyFor;

/**
 * A map of comparable keys to values. Unlike {@code TreeMap}, this class uses
 * insertion order for iteration order. Comparison order is only used as an
 * optimization for efficient insertion and removal.
 *
 * <p>This implementation was derived from Android 4.1's TreeMap and
 * LinkedHashMap classes.
 */
public final class LinkedHashTreeMap<K, V> extends AbstractMap<K, V> implements Serializable {
  @SuppressWarnings({ "unchecked", "rawtypes" }) // to avoid Comparable<Comparable<Comparable<...>>>
  private static final Comparator<Comparable> NATURAL_ORDER = new Comparator<Comparable>() {
    public int compare(Comparable a, Comparable b) {
      return a.compareTo(b);
    }
  };

  Comparator<? super K> comparator;
  @Nullable Node<K, V>[] table;
  final Node<K, V> header;
  int size = 0;
  int modCount = 0;
  int threshold;

  /**
   * Create a natural order, empty tree map whose keys must be mutually
   * comparable and non-null.
   */
  @SuppressWarnings("unchecked") // unsafe! this assumes K is comparable
  public LinkedHashTreeMap() {
    this((Comparator<? super K>) NATURAL_ORDER);
  }

  /**
   * Create a tree map ordered by {@code comparator}. This map's keys may only
   * be null if {@code comparator} permits.
   *
   * @param comparator the comparator to order elements with, or {@code null} to
   *     use the natural ordering.
   */

   /*entrySet and keySet are private variables used
   by the function entrySet() and keySet()*/
  @SuppressWarnings({ "unchecked", "rawtypes", "initialization.fields.uninitialized"}) // unsafe! if comparator is null, this assumes K is comparable
  public LinkedHashTreeMap(Comparator<? super K> comparator) {
    this.comparator = comparator != null
        ? comparator
        : (Comparator) NATURAL_ORDER;
    this.header = new Node<K, V>();
    this.table = new Node[16]; // TODO: sizing/resizing policies
    this.threshold = (table.length / 2) + (table.length / 4); // 3/4 capacity
  }

  @Override public int size() {
    return size;
  }

  @Override public @Nullable V get(Object key) {
    Node<K, V> node = findByObject(key);
    return node != null ? node.value : null;
  }

  @Override public boolean containsKey(@Nullable Object key) {
    return findByObject(key) != null;
  }

  @Override public @Nullable V put(K key, V value) {
    if (key == null) {
      throw new NullPointerException("key == null");
    }
    Node<K, V> created = find(key, true);
    /*created was not ensured to be non null*/
    @SuppressWarnings("dereference.of.nullable")
    V result = created.value;
    created.value = value;
    return result;
  }

  /*null is assigned to clear links in #1 and the node is
  never used so the code is safe for this situation*/
  @SuppressWarnings("assignment.type.incompatible")
  @Override public void clear() {
    Arrays.fill(table, null);
    size = 0;
    modCount++;

    // Clear all links to help GC
    Node<K, V> header = this.header;
    for (Node<K, V> e = header.next; e != header; ) {
      Node<K, V> next = e.next;
      e.next = e.prev = null; //#1
      e = next;
    }

    header.next = header.prev = header;
  }

  @Override public @Nullable V remove(@Nullable Object key) {
    Node<K, V> node = removeInternalByKey(key);
    return node != null ? node.value : null;
  }

  /**
   * Returns the node at or adjacent to the given key, creating it if requested.
   *
   * @throws ClassCastException if {@code key} and the tree's keys aren't
   *     mutually comparable.
   */
   /*nearest.key was not ensured to be non-null before sending to compareTo #2
   if this function returns non null value, it is ensured that the arg `key` is a key for this Map */
  @SuppressWarnings({"nullness:argument.type.incompatible","contracts.postcondition.not.satisfied"})
  @EnsuresKeyFor(value="#1", map ="this")
  @Nullable Node<K, V> find(@NonNull K key, boolean create) {
    Comparator<? super K> comparator = this.comparator;
    @Nullable Node<K, V>[] table = this.table;
    int hash = secondaryHash(key.hashCode());
    int index = hash & (table.length - 1);
    Node<K, V> nearest = table[index];
    int comparison = 0;

    if (nearest != null) {
      // Micro-optimization: avoid polymorphic calls to Comparator.compare().
      @SuppressWarnings("unchecked") // Throws a ClassCastException below if there's trouble.
      Comparable<Object> comparableKey = (comparator == NATURAL_ORDER)
          ? (Comparable<Object>) key
          : null;

      while (true) {
        comparison = (comparableKey != null)
            ? comparableKey.compareTo(nearest.key) //#2
            : comparator.compare(key, nearest.key);

        // We found the requested key.
        if (comparison == 0) {
          return nearest;
        }

        // If it exists, the key is in a subtree. Go deeper.
        Node<K, V> child = (comparison < 0) ? nearest.left : nearest.right;
        if (child == null) {
          break;
        }

        nearest = child;
      }
    }

    // The key doesn't exist in this tree.
    if (!create) {
      return null;
    }

    // Create the node and add it to the tree or the table.
    Node<K, V> header = this.header;
    Node<K, V> created;
    if (nearest == null) {
      // Check that the value is comparable if we didn't do any comparisons.
      if (comparator == NATURAL_ORDER && !(key instanceof Comparable)) {
        throw new ClassCastException(key.getClass().getName() + " is not Comparable");
      }
      created = new Node<K, V>(nearest, key, hash, header, header.prev);
      table[index] = created;
    } else {
      created = new Node<K, V>(nearest, key, hash, header, header.prev);
      if (comparison < 0) { // nearest.key is higher
        nearest.left = created;
      } else { // comparison > 0, nearest.key is lower
        nearest.right = created;
      }
      rebalance(nearest, true);
    }

    if (size++ > threshold) {
      doubleCapacity();
    }
    modCount++;

    return created;
  }

  /*key is ensured to be non null before sending to find #16
  if this function returns non null value, it is ensured that the arg `key` is a key for this Map #16*/
  @SuppressWarnings({"unchecked","nullness:argument.type.incompatible","contracts.postcondition.not.satisfied"})
  @EnsuresKeyFor(value="#1", map="this")
  @Nullable Node<K, V> findByObject(@Nullable Object key) {
    try {
      return key != null ? find((K) key, false) : null; //#16
    } catch (ClassCastException e) {
      return null;
    }
  }

  /**
   * Returns this map's entry that has the same key and value as {@code
   * entry}, or null if this map has no such entry.
   *
   * <p>This method uses the comparator for key equality rather than {@code
   * equals}. If this map's comparator isn't consistent with equals (such as
   * {@code String.CASE_INSENSITIVE_ORDER}), then {@code remove()} and {@code
   * contains()} will violate the collections API.
   */
  @Nullable Node<K, V> findByEntry(Entry<?, ?> entry) {
    Node<K, V> mine = findByObject(entry.getKey());
    boolean valuesEqual = mine != null && equal(mine.value, entry.getValue());
    return valuesEqual ? mine : null;
  }

  private boolean equal(@Nullable Object a, @Nullable Object b) {
    return a == b || (a != null && a.equals(b));
  }

  /**
   * Applies a supplemental hash function to a given hashCode, which defends
   * against poor quality hash functions. This is critical because HashMap
   * uses power-of-two length hash tables, that otherwise encounter collisions
   * for hashCodes that do not differ in lower or upper bits.
   */
  private static int secondaryHash(int h) {
    // Doug Lea's supplemental hash function
    h ^= (h >>> 20) ^ (h >>> 12);
    return h ^ (h >>> 7) ^ (h >>> 4);
  }

  /**
   * Removes {@code node} from this tree, rearranging the tree's structure as
   * necessary.
   *
   * @param unlink true to also unlink this node from the iteration linked list.
   */

   /*null is assigned in #3 to remove link to the tree and
   the node is never used so it is safe for this situation*/
  @SuppressWarnings({"nullness:assignment.type.incompatible"})
  void removeInternal(Node<K, V> node, boolean unlink) {
    if (unlink) {
      node.prev.next = node.next;
      node.next.prev = node.prev;
      node.next = node.prev = null; // Help the GC (for performance) //#3
    }

    Node<K, V> left = node.left;
    Node<K, V> right = node.right;
    Node<K, V> originalParent = node.parent;
    if (left != null && right != null) {

      /*
       * To remove a node with both left and right subtrees, move an
       * adjacent node from one of those subtrees into this node's place.
       *
       * Removing the adjacent node may change this node's subtrees. This
       * node may no longer have two subtrees once the adjacent node is
       * gone!
       */

      Node<K, V> adjacent = (left.height > right.height) ? left.last() : right.first();
      removeInternal(adjacent, false); // takes care of rebalance and size--

      int leftHeight = 0;
      left = node.left;
      if (left != null) {
        leftHeight = left.height;
        adjacent.left = left;
        left.parent = adjacent;
        node.left = null;
      }
      int rightHeight = 0;
      right = node.right;
      if (right != null) {
        rightHeight = right.height;
        adjacent.right = right;
        right.parent = adjacent;
        node.right = null;
      }
      adjacent.height = Math.max(leftHeight, rightHeight) + 1;
      replaceInParent(node, adjacent);
      return;
    } else if (left != null) {
      replaceInParent(node, left);
      node.left = null;
    } else if (right != null) {
      replaceInParent(node, right);
      node.right = null;
    } else {
      replaceInParent(node, null);
    }

    rebalance(originalParent, false);
    size--;
    modCount++;
  }

  @Nullable Node<K, V> removeInternalByKey(@Nullable Object key) {
    Node<K, V> node = findByObject(key);
    if (node != null) {
      removeInternal(node, true);
    }
    return node;
  }

  private void replaceInParent(Node<K, V> node, @Nullable Node<K, V> replacement) {
    Node<K, V> parent = node.parent;
    node.parent = null;
    if (replacement != null) {
      replacement.parent = parent;
    }

    if (parent != null) {
      if (parent.left == node) {
        parent.left = replacement;
      } else {
        assert (parent.right == node);
        parent.right = replacement;
      }
    } else {
      int index = node.hash & (table.length - 1);
      table[index] = replacement;
    }
  }

  /**
   * Rebalances the tree by making any AVL rotations necessary between the
   * newly-unbalanced node and the tree's root.
   *
   * @param insert true if the node was unbalanced by an insert; false if it
   *     was by a removal.
   */
  private void rebalance(@Nullable Node<K, V> unbalanced, boolean insert) {
    for (Node<K, V> node = unbalanced; node != null; node = node.parent) {
      Node<K, V> left = node.left;
      Node<K, V> right = node.right;
      int leftHeight = left != null ? left.height : 0;
      int rightHeight = right != null ? right.height : 0;

      int delta = leftHeight - rightHeight;
      if (delta == -2) { //#5
        /*as delta is -2 #5 right cannot be null, right.left is safe #4*/
        @SuppressWarnings("dereference.of.nullable")
        Node<K, V> rightLeft = right.left; //#4
        Node<K, V> rightRight = right.right;
        int rightRightHeight = rightRight != null ? rightRight.height : 0;
        int rightLeftHeight = rightLeft != null ? rightLeft.height : 0;

        int rightDelta = rightLeftHeight - rightRightHeight;
        if (rightDelta == -1 || (rightDelta == 0 && !insert)) {
          rotateLeft(node); // AVL right right
        } else {
          assert (rightDelta == 1);
          rotateRight(right); // AVL right left
          rotateLeft(node);
        }
        if (insert) {
          break; // no further rotations will be necessary
        }

      } else if (delta == 2) { //#7
        /*as delta is 2 #7 left cannot be null, right.left or right is safe #6*/
        @SuppressWarnings("dereference.of.nullable")
        Node<K, V> leftLeft = left.left; //#6
        Node<K, V> leftRight = left.right;
        int leftRightHeight = leftRight != null ? leftRight.height : 0;
        int leftLeftHeight = leftLeft != null ? leftLeft.height : 0;

        int leftDelta = leftLeftHeight - leftRightHeight;
        if (leftDelta == 1 || (leftDelta == 0 && !insert)) {
          rotateRight(node); // AVL left left
        } else {
          assert (leftDelta == -1);
          rotateLeft(left); // AVL left right
          rotateRight(node);
        }
        if (insert) {
          break; // no further rotations will be necessary
        }

      } else if (delta == 0) {
        node.height = leftHeight + 1; // leftHeight == rightHeight
        if (insert) {
          break; // the insert caused balance, so rebalancing is done!
        }

      } else {
        assert (delta == -1 || delta == 1);
        node.height = Math.max(leftHeight, rightHeight) + 1;
        if (!insert) {
          break; // the height hasn't changed, so rebalancing is done!
        }
      }
    }
  }

  /**
   * Rotates the subtree so that its root's right child is the new root.
   */
   /*rotateLeft function is private and called only if root.right exists,
   pivot is not null #8 in this case*/
   @SuppressWarnings("dereference.of.nullable")
  private void rotateLeft(Node<K, V> root) {
    Node<K, V> left = root.left;
    Node<K, V> pivot = root.right; //#8
    Node<K, V> pivotLeft = pivot.left;
    Node<K, V> pivotRight = pivot.right;

    // move the pivot's left child to the root's right
    root.right = pivotLeft;
    if (pivotLeft != null) {
      pivotLeft.parent = root;
    }

    replaceInParent(root, pivot);

    // move the root to the pivot's left
    pivot.left = root;
    root.parent = pivot;

    // fix heights
    root.height = Math.max(left != null ? left.height : 0,
        pivotLeft != null ? pivotLeft.height : 0) + 1;
    pivot.height = Math.max(root.height,
        pivotRight != null ? pivotRight.height : 0) + 1;
  }

  /**
   * Rotates the subtree so that its root's left child is the new root.
   */
   /*rotateRight function is private and called only if root.left exists,
   pivot is not null #9 in this case*/
   @SuppressWarnings("dereference.of.nullable")
  private void rotateRight(Node<K, V> root) {
    Node<K, V> pivot = root.left; //#9
    Node<K, V> right = root.right;
    Node<K, V> pivotLeft = pivot.left;
    Node<K, V> pivotRight = pivot.right;

    // move the pivot's right child to the root's left
    root.left = pivotRight;
    if (pivotRight != null) {
      pivotRight.parent = root;
    }

    replaceInParent(root, pivot);

    // move the root to the pivot's right
    pivot.right = root;
    root.parent = pivot;

    // fixup heights
    root.height = Math.max(right != null ? right.height : 0,
        pivotRight != null ? pivotRight.height : 0) + 1;
    pivot.height = Math.max(root.height,
        pivotLeft != null ? pivotLeft.height : 0) + 1;
  }

  private EntrySet entrySet;
  private KeySet keySet;

  @Override public Set<Entry<@KeyFor("this") K, V>> entrySet() {
    EntrySet result = entrySet;
    return result != null ? result : (entrySet = new EntrySet());
  }

  @Override public Set<@KeyFor("this") K> keySet() {
    KeySet result = keySet;
    return result != null ? result : (keySet = new KeySet());
  }

  static final class Node<K, V> implements Entry<K, V> {
    @Nullable Node<K, V> parent;
    @Nullable Node<K, V> left;
    @Nullable Node<K, V> right;
    Node<K, V> next;
    Node<K, V> prev;
    final K key;
    final int hash;
    @Nullable V value;
    int height;

    /** Create the header entry */
    /*This constructor is called only to create header whose key can be null #10*/
    @SuppressWarnings({"nullness:assignment.type.incompatible"})
    Node() {
      key = null; //#10
      hash = -1;
      next = prev = this;
    }

    /** Create a regular entry */
    Node(Node<K, V> parent, K key, int hash, Node<K, V> next, Node<K, V> prev) {
      this.parent = parent;
      this.key = key;
      this.hash = hash;
      this.height = 1;
      this.next = next;
      this.prev = prev;
      prev.next = this;
      next.prev = this;
    }

    public K getKey() {
      return key;
    }

    /*constructor does not initialize value
    therefore getValue might return null*/
    @SuppressWarnings("nullness:override.return.invalid")
    public @Nullable V getValue() {
      return value;
    }

    /*constructor does not initialize value
    therefore returned oldValue #11 might be null*/
    @SuppressWarnings("nullness:override.return.invalid")
    public @Nullable V setValue(V value) {
      V oldValue = this.value;
      this.value = value;
      return oldValue; //#11
    }

    @SuppressWarnings("rawtypes")
    @Override public boolean equals(@Nullable Object o) {
      if (o instanceof Entry) {
        Entry other = (Entry) o;
        return (key == null ? other.getKey() == null : key.equals(other.getKey()))
            && (value == null ? other.getValue() == null : value.equals(other.getValue()));
      }
      return false;
    }

    @Override public int hashCode() {
      return (key == null ? 0 : key.hashCode())
          ^ (value == null ? 0 : value.hashCode());
    }

    @Override public String toString() {
      return key + "=" + value;
    }

    /**
     * Returns the first node in this subtree.
     */
    public Node<K, V> first() {
      Node<K, V> node = this;
      Node<K, V> child = node.left;
      while (child != null) {
        node = child;
        child = node.left;
      }
      return node;
    }

    /**
     * Returns the last node in this subtree.
     */
    public Node<K, V> last() {
      Node<K, V> node = this;
      Node<K, V> child = node.right;
      while (child != null) {
        node = child;
        child = node.right;
      }
      return node;
    }
  }

  private void doubleCapacity() {
    table = doubleCapacity(table);
    threshold = (table.length / 2) + (table.length / 4); // 3/4 capacity
  }

  /**
   * Returns a new array containing the same nodes as {@code oldTable}, but with
   * twice as many trees, each of (approximately) half the previous size.
   */
  static <K, V> @Nullable Node<K, V>[] doubleCapacity(@Nullable Node<K, V>[] oldTable) {
    // TODO: don't do anything if we're already at MAX_CAPACITY
    int oldCapacity = oldTable.length;
    @SuppressWarnings("unchecked") // Arrays and generics don't get along.
    @Nullable Node<K, V>[] newTable = new Node[oldCapacity * 2];
    AvlIterator<K, V> iterator = new AvlIterator<K, V>();
    AvlBuilder<K, V> leftBuilder = new AvlBuilder<K, V>();
    AvlBuilder<K, V> rightBuilder = new AvlBuilder<K, V>();

    // Split each tree into two trees.
    for (int i = 0; i < oldCapacity; i++) {
      Node<K, V> root = oldTable[i];
      if (root == null) {
        continue;
      }

      // Compute the sizes of the left and right trees.
      iterator.reset(root);
      int leftSize = 0;
      int rightSize = 0;
      for (Node<K, V> node; (node = iterator.next()) != null; ) {
        if ((node.hash & oldCapacity) == 0) {
          leftSize++;
        } else {
          rightSize++;
        }
      }

      // Split the tree into two.
      leftBuilder.reset(leftSize);
      rightBuilder.reset(rightSize);
      iterator.reset(root);
      for (Node<K, V> node; (node = iterator.next()) != null; ) {
        if ((node.hash & oldCapacity) == 0) {
          leftBuilder.add(node);
        } else {
          rightBuilder.add(node);
        }
      }

      // Populate the enlarged array with these new roots.
      newTable[i] = leftSize > 0 ? leftBuilder.root() : null;
      newTable[i + oldCapacity] = rightSize > 0 ? rightBuilder.root() : null;
    }
    return newTable;
  }

  /**
   * Walks an AVL tree in iteration order. Once a node has been returned, its
   * left, right and parent links are <strong>no longer used</strong>. For this
   * reason it is safe to transform these links as you walk a tree.
   *
   * <p><strong>Warning:</strong> this iterator is destructive. It clears the
   * parent node of all nodes in the tree. It is an error to make a partial
   * iteration of a tree.
   */
  static class AvlIterator<K, V> {
    /** This stack is a singly linked list, linked by the 'parent' field. */
    private @Nullable Node<K, V> stackTop;

    void reset(Node<K, V> root) {
      Node<K, V> stackTop = null;
      for (Node<K, V> n = root; n != null; n = n.left) {
        n.parent = stackTop;
        stackTop = n; // Stack push.
      }
      this.stackTop = stackTop;
    }

    public @Nullable Node<K, V> next() {
      Node<K, V> stackTop = this.stackTop;
      if (stackTop == null) {
        return null;
      }
      Node<K, V> result = stackTop;
      stackTop = result.parent;
      result.parent = null;
      for (Node<K, V> n = result.right; n != null; n = n.left) {
        n.parent = stackTop;
        stackTop = n; // Stack push.
      }
      this.stackTop = stackTop;
      return result;
    }
  }

  /**
   * Builds AVL trees of a predetermined size by accepting nodes of increasing
   * value. To use:
   * <ol>
   *   <li>Call {@link #reset} to initialize the target size <i>size</i>.
   *   <li>Call {@link #add} <i>size</i> times with increasing values.
   *   <li>Call {@link #root} to get the root of the balanced tree.
   * </ol>
   *
   * <p>The returned tree will satisfy the AVL constraint: for every node
   * <i>N</i>, the height of <i>N.left</i> and <i>N.right</i> is different by at
   * most 1. It accomplishes this by omitting deepest-level leaf nodes when
   * building trees whose size isn't a power of 2 minus 1.
   *
   * <p>Unlike rebuilding a tree from scratch, this approach requires no value
   * comparisons. Using this class to create a tree of size <i>S</i> is
   * {@code O(S)}.
   */
  final static class AvlBuilder<K, V> {
    /** This stack is a singly linked list, linked by the 'parent' field. */
    private @Nullable Node<K, V> stack;
    private int leavesToSkip;
    private int leavesSkipped;
    private int size;

    void reset(int targetSize) {
      // compute the target tree size. This is a power of 2 minus one, like 15 or 31.
      int treeCapacity = Integer.highestOneBit(targetSize) * 2 - 1;
      leavesToSkip = treeCapacity - targetSize;
      size = 0;
      leavesSkipped = 0;
      stack = null;
    }

    /*leavesSkipped represent the current state of stack in #12 #13 #14,
    therefore all assignments inside the ifs are non null and
    has no dereference of nullable, therefore code is safe*/
    @SuppressWarnings("dereference.of.nullable")
    void add(Node<K, V> node) {
      node.left = node.parent = node.right = null;
      node.height = 1;

      // Skip a leaf if necessary.
      if (leavesToSkip > 0 && (size & 1) == 0) {
        size++;
        leavesToSkip--;
        leavesSkipped++;
      }

      node.parent = stack;
      stack = node; // Stack push.
      size++;

      // Skip a leaf if necessary.
      if (leavesToSkip > 0 && (size & 1) == 0) {
        size++;
        leavesToSkip--;
        leavesSkipped++;
      }

      /*
       * Combine 3 nodes into subtrees whenever the size is one less than a
       * multiple of 4. For example we combine the nodes A, B, C into a
       * 3-element tree with B as the root.
       *
       * Combine two subtrees and a spare single value whenever the size is one
       * less than a multiple of 8. For example at 8 we may combine subtrees
       * (A B C) and (E F G) with D as the root to form ((A B C) D (E F G)).
       *
       * Just as we combine single nodes when size nears a multiple of 4, and
       * 3-element trees when size nears a multiple of 8, we combine subtrees of
       * size (N-1) whenever the total size is 2N-1 whenever N is a power of 2.
       */
      for (int scale = 4; (size & scale - 1) == scale - 1; scale *= 2) {
        if (leavesSkipped == 0) { //#12
          // Pop right, center and left, then make center the top of the stack.
          Node<K, V> right = stack;
          Node<K, V> center = right.parent;
          Node<K, V> left = center.parent;
          center.parent = left.parent;
          stack = center;
          // Construct a tree.
          center.left = left;
          center.right = right;
          center.height = right.height + 1;
          left.parent = center;
          right.parent = center;
        } else if (leavesSkipped == 1) { //#13
          // Pop right and center, then make center the top of the stack.
          Node<K, V> right = stack;
          Node<K, V> center = right.parent;
          stack = center;
          // Construct a tree with no left child.
          center.right = right;
          center.height = right.height + 1;
          right.parent = center;
          leavesSkipped = 0;
        } else if (leavesSkipped == 2) { //#14
          leavesSkipped = 0;
        }
      }
    }

    /*if the below function is called without adding anything to the stack,
    dereference of nullable is possible #15*/
    @SuppressWarnings("dereference.of.nullable")
    Node<K, V> root() {
      Node<K, V> stackTop = this.stack;
      if (stackTop.parent != null) { //#15
        throw new IllegalStateException();
      }
      return stackTop;
    }
  }

  private abstract class LinkedTreeMapIterator<@KeyFor("this") T> implements Iterator<T> {
    Node<K, V> next = header.next;
    @Nullable Node<K, V> lastReturned = null;
    int expectedModCount = modCount;

    LinkedTreeMapIterator() {
    }

    public final boolean hasNext() {
      return next != header;
    }

    final Node<K, V> nextNode() {
      Node<K, V> e = next;
      if (e == header) {
        throw new NoSuchElementException();
      }
      if (modCount != expectedModCount) {
        throw new ConcurrentModificationException();
      }
      next = e.next;
      return lastReturned = e;
    }

    public final void remove() {
      if (lastReturned == null) {
        throw new IllegalStateException();
      }
      removeInternal(lastReturned, true);
      lastReturned = null;
      expectedModCount = modCount;
    }
  }

  final class EntrySet extends AbstractSet<Entry<@KeyFor("this") K, V>> {
    @Override public int size() {
      return size;
    }

    @Override public Iterator<Entry<@KeyFor("this") K, V>> iterator() {
      return new LinkedTreeMapIterator<Entry<@KeyFor("this") K, V>>() {
        /*#18 would return a key for the next node which is a node from the map,
        therefore returned key is a key of `this` Map*/
        @SuppressWarnings("return.type.incompatible")
        public Entry<@KeyFor("this") K, V> next() {
          return nextNode(); //#18
        }
      };
    }

    @Override public boolean contains(@Nullable Object o) {
      return o instanceof Entry && findByEntry((Entry<?, ?>) o) != null;
    }

    @Override public boolean remove(@Nullable Object o) {
      if (!(o instanceof Entry)) {
        return false;
      }

      Node<K, V> node = findByEntry((Entry<?, ?>) o);
      if (node == null) {
        return false;
      }
      removeInternal(node, true);
      return true;
    }

    @Override public void clear() {
      LinkedHashTreeMap.this.clear();
    }
  }

  final class KeySet extends AbstractSet<@KeyFor("this") K> {
    @Override public int size() {
      return size;
    }

    @Override public Iterator<@KeyFor("this") K> iterator() {
      return new LinkedTreeMapIterator<@KeyFor("this") K>() {
        /*#17 would return a key for the next node which is a node from the map,
        therefore returned key is a key of `this` Map*/
        @SuppressWarnings("return.type.incompatible")
        public @KeyFor("this") K next() {
          return nextNode().key; //#17
        }
      };
    }

    @Override public boolean contains(@Nullable Object o) {
      return containsKey(o);
    }

    @Override public boolean remove(@Nullable Object key) {
      return removeInternalByKey(key) != null;
    }

    @Override public void clear() {
      LinkedHashTreeMap.this.clear();
    }
  }

  /**
   * If somebody is unlucky enough to have to serialize one of these, serialize
   * it as a LinkedHashMap so that they won't need Gson on the other side to
   * deserialize it. Using serialization defeats our DoS defence, so most apps
   * shouldn't use it.
   */
  private Object writeReplace() throws ObjectStreamException {
    return new LinkedHashMap<K, V>(this);
  }
}
