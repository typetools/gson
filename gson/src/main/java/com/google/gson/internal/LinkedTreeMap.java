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
 * <p>This implementation was derived from Android 4.1's TreeMap class.
 */
public final class LinkedTreeMap<K, V> extends AbstractMap<K, V> implements Serializable {
  @SuppressWarnings({ "unchecked", "rawtypes" }) // to avoid Comparable<Comparable<Comparable<...>>>
  private static final Comparator<Comparable> NATURAL_ORDER = new Comparator<Comparable>() {
    public int compare(Comparable a, Comparable b) {
      return a.compareTo(b);
    }
  };

  Comparator<? super K> comparator;
  @Nullable Node<K, V> root;
  int size = 0;
  int modCount = 0;

  // Used to preserve iteration order
  final Node<K, V> header = new Node<K, V>();

  /**
   * Create a natural order, empty tree map whose keys must be mutually
   * comparable and non-null.
   */
  @SuppressWarnings("unchecked") // unsafe! this assumes K is comparable
  public LinkedTreeMap() {
    this((Comparator<? super K>) NATURAL_ORDER);
  }

  /**
   * Create a tree map ordered by {@code comparator}. This map's keys may only
   * be null if {@code comparator} permits.
   *
   * @param comparator the comparator to order elements with, or {@code null} to
   *     use the natural ordering.
   */
   //keySet and entrySet are private fields used only by functions keySet() entrySet()
  @SuppressWarnings({ "unchecked", "rawtypes", "initialization.fields.uninitialized"}) // unsafe! if comparator is null, this assumes K is comparable
  public LinkedTreeMap(Comparator<? super K> comparator) {
    this.comparator = comparator != null
        ? comparator
        : (Comparator) NATURAL_ORDER;
  }

  @Override public int size() {
    return size;
  }

  @Override public @Nullable V get(@Nullable Object key) {
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
    /*the code does not ensure that `created` returned by find is non null*/
    @SuppressWarnings("dereference.of.nullable")
    V result = created.value;
    created.value = value;
    return result;
  }

  @Override public void clear() {
    root = null;
    size = 0;
    modCount++;

    // Clear iteration order
    Node<K, V> header = this.header;
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
   /*nearest.key was not ensured to be non-null before sending to compareTo #1,
   if this function returns non null value, it is ensured that the arg `key` is a key for this Map */
  @SuppressWarnings({"nullness:argument.type.incompatible","contracts.postcondition.not.satisfied"})
  @EnsuresKeyFor(value={"#1"}, map={"this"})
  @Nullable Node<K, V> find(@NonNull K key, boolean create) {
    Comparator<? super K> comparator = this.comparator;
    Node<K, V> nearest = root;
    int comparison = 0;

    if (nearest != null) {
      // Micro-optimization: avoid polymorphic calls to Comparator.compare().
      @SuppressWarnings("unchecked") // Throws a ClassCastException below if there's trouble.
          Comparable<Object> comparableKey = (comparator == NATURAL_ORDER)
          ? (Comparable<Object>) key
          : null;

      while (true) {
        comparison = (comparableKey != null)
            ? comparableKey.compareTo(nearest.key) //#1
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
      created = new Node<K, V>(nearest, key, header, header.prev);
      root = created;
    } else {
      created = new Node<K, V>(nearest, key, header, header.prev);
      if (comparison < 0) { // nearest.key is higher
        nearest.left = created;
      } else { // comparison > 0, nearest.key is lower
        nearest.right = created;
      }
      rebalance(nearest, true);
    }
    size++;
    modCount++;

    return created;
  }

  /*key is ensured to be non null before sending it to find #2,
  if this function returns non null value, it is ensured that the arg `key` is a key for this Map #2*/
  @SuppressWarnings({"unchecked", "nullness:argument.type.incompatible","contracts.postcondition.not.satisfied"})
  @EnsuresKeyFor(value={"#1"}, map={"this"})
  @Nullable Node<K, V> findByObject(@Nullable Object key) {
    try {
      return key != null ? find((K) key, false) : null; //#2
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
   * Removes {@code node} from this tree, rearranging the tree's structure as
   * necessary.
   *
   * @param unlink true to also unlink this node from the iteration linked list.
   */
  void removeInternal(Node<K, V> node, boolean unlink) {
    if (unlink) {
      node.prev.next = node.next;
      node.next.prev = node.prev;
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
      root = replacement;
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
      if (delta == -2) { //#3
        /*as delta is -2 #3 right cannot be null, right.left #4 and right.right is safe */
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

      } else if (delta == 2) { //#5
        /*as delta is 2 #5 left cannot be null, left.left #6 and left.right is safe */
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
   /*rotateLeft is a private function and is called only if root right exists,
   therefore pivot is not null in this case #7*/
   @SuppressWarnings("dereference.of.nullable")
  private void rotateLeft(Node<K, V> root) {
    Node<K, V> left = root.left;
    Node<K, V> pivot = root.right; //#7
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
   /*rotateRight is a private function and is called only if root left exists,
   therefore pivot is not null in this case #8*/
   @SuppressWarnings("dereference.of.nullable")
  private void rotateRight(Node<K, V> root) {
    Node<K, V> pivot = root.left; //#8
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

  @Override public Set<Entry<@KeyFor({"this"}) K, V>> entrySet() {
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
    @Nullable V value;
    int height;

    /** Create the header entry */
    /*this constructor is called to create header whose key can be null,
    dangerous to use `this` an underinitialized object for assignment*/
    @SuppressWarnings("assignment.type.incompatible")
    Node() {
      key = null;
      next = prev = this;
    }

    /** Create a regular entry */
    Node(Node<K, V> parent, K key, Node<K, V> next, Node<K, V> prev) {
      this.parent = parent;
      this.key = key;
      this.height = 1;
      this.next = next;
      this.prev = prev;
      prev.next = this;
      next.prev = this;
    }

    public K getKey() {
      return key;
    }

    //constructor does not initialize value, therefore this function might return null
    @SuppressWarnings("override.return.invalid")
    public @Nullable V getValue() {
      return value;
    }

    /*constructor does not initialize value, therefore oldValue might be null,
    and this function can return null*/
    @SuppressWarnings("override.return.invalid")
    public @Nullable V setValue(V value) {
      V oldValue = this.value;
      this.value = value;
      return oldValue;
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

  class EntrySet extends AbstractSet<Entry<@KeyFor("this") K, V>> {
    @Override public int size() {
      return size;
    }

    @Override public Iterator<Entry<@KeyFor("this") K, V>> iterator() {
      return new LinkedTreeMapIterator<Entry<@KeyFor("this") K, V>>() {
        /*#9 would return a key for the next node which is a node from the map,
        therefore returned key is a key of `this` Map*/
        @SuppressWarnings("return.type.incompatible")
        public Entry<@KeyFor("this") K, V> next() {
          return nextNode(); //10
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
      LinkedTreeMap.this.clear();
    }
  }

  final class KeySet extends AbstractSet<@KeyFor("this") K> {
    @Override public int size() {
      return size;
    }

    @Override public Iterator<@KeyFor("this") K> iterator() {
      return new LinkedTreeMapIterator<@KeyFor("this") K>() {
        /*#9 would return a key for the next node which is a node from the map,
        therefore returned key is a key of `this` Map*/
        @SuppressWarnings("return.type.incompatible")
        public @KeyFor("this") K next() {
          return nextNode().key; //#9
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
      LinkedTreeMap.this.clear();
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
