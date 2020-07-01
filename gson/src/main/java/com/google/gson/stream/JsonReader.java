/*
 * Copyright (C) 2010 Google Inc.
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

package com.google.gson.stream;

import com.google.gson.internal.JsonReaderInternalAccess;
import com.google.gson.internal.bind.JsonTreeReader;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.index.qual.LTLengthOf;
import org.checkerframework.checker.index.qual.LTEqLengthOf;
import org.checkerframework.checker.index.qual.IndexFor;
import org.checkerframework.checker.index.qual.GTENegativeOne;

/**
 * Reads a JSON (<a href="http://www.ietf.org/rfc/rfc7159.txt">RFC 7159</a>)
 * encoded value as a stream of tokens. This stream includes both literal
 * values (strings, numbers, booleans, and nulls) as well as the begin and
 * end delimiters of objects and arrays. The tokens are traversed in
 * depth-first order, the same order that they appear in the JSON document.
 * Within JSON objects, name/value pairs are represented by a single token.
 *
 * <h3>Parsing JSON</h3>
 * To create a recursive descent parser for your own JSON streams, first create
 * an entry point method that creates a {@code JsonReader}.
 *
 * <p>Next, create handler methods for each structure in your JSON text. You'll
 * need a method for each object type and for each array type.
 * <ul>
 *   <li>Within <strong>array handling</strong> methods, first call {@link
 *       #beginArray} to consume the array's opening bracket. Then create a
 *       while loop that accumulates values, terminating when {@link #hasNext}
 *       is false. Finally, read the array's closing bracket by calling {@link
 *       #endArray}.
 *   <li>Within <strong>object handling</strong> methods, first call {@link
 *       #beginObject} to consume the object's opening brace. Then create a
 *       while loop that assigns values to local variables based on their name.
 *       This loop should terminate when {@link #hasNext} is false. Finally,
 *       read the object's closing brace by calling {@link #endObject}.
 * </ul>
 * <p>When a nested object or array is encountered, delegate to the
 * corresponding handler method.
 *
 * <p>When an unknown name is encountered, strict parsers should fail with an
 * exception. Lenient parsers should call {@link #skipValue()} to recursively
 * skip the value's nested tokens, which may otherwise conflict.
 *
 * <p>If a value may be null, you should first check using {@link #peek()}.
 * Null literals can be consumed using either {@link #nextNull()} or {@link
 * #skipValue()}.
 *
 * <h3>Example</h3>
 * Suppose we'd like to parse a stream of messages such as the following: <pre> {@code
 * [
 *   {
 *     "id": 912345678901,
 *     "text": "How do I read a JSON stream in Java?",
 *     "geo": null,
 *     "user": {
 *       "name": "json_newb",
 *       "followers_count": 41
 *      }
 *   },
 *   {
 *     "id": 912345678902,
 *     "text": "@json_newb just use JsonReader!",
 *     "geo": [50.454722, -104.606667],
 *     "user": {
 *       "name": "jesse",
 *       "followers_count": 2
 *     }
 *   }
 * ]}</pre>
 * This code implements the parser for the above structure: <pre>   {@code
 *
 *   public List<Message> readJsonStream(InputStream in) throws IOException {
 *     JsonReader reader = new JsonReader(new InputStreamReader(in, "UTF-8"));
 *     try {
 *       return readMessagesArray(reader);
 *     } finally {
 *       reader.close();
 *     }
 *   }
 *
 *   public List<Message> readMessagesArray(JsonReader reader) throws IOException {
 *     List<Message> messages = new ArrayList<Message>();
 *
 *     reader.beginArray();
 *     while (reader.hasNext()) {
 *       messages.add(readMessage(reader));
 *     }
 *     reader.endArray();
 *     return messages;
 *   }
 *
 *   public Message readMessage(JsonReader reader) throws IOException {
 *     long id = -1;
 *     String text = null;
 *     User user = null;
 *     List<Double> geo = null;
 *
 *     reader.beginObject();
 *     while (reader.hasNext()) {
 *       String name = reader.nextName();
 *       if (name.equals("id")) {
 *         id = reader.nextLong();
 *       } else if (name.equals("text")) {
 *         text = reader.nextString();
 *       } else if (name.equals("geo") && reader.peek() != JsonToken.NULL) {
 *         geo = readDoublesArray(reader);
 *       } else if (name.equals("user")) {
 *         user = readUser(reader);
 *       } else {
 *         reader.skipValue();
 *       }
 *     }
 *     reader.endObject();
 *     return new Message(id, text, user, geo);
 *   }
 *
 *   public List<Double> readDoublesArray(JsonReader reader) throws IOException {
 *     List<Double> doubles = new ArrayList<Double>();
 *
 *     reader.beginArray();
 *     while (reader.hasNext()) {
 *       doubles.add(reader.nextDouble());
 *     }
 *     reader.endArray();
 *     return doubles;
 *   }
 *
 *   public User readUser(JsonReader reader) throws IOException {
 *     String username = null;
 *     int followersCount = -1;
 *
 *     reader.beginObject();
 *     while (reader.hasNext()) {
 *       String name = reader.nextName();
 *       if (name.equals("name")) {
 *         username = reader.nextString();
 *       } else if (name.equals("followers_count")) {
 *         followersCount = reader.nextInt();
 *       } else {
 *         reader.skipValue();
 *       }
 *     }
 *     reader.endObject();
 *     return new User(username, followersCount);
 *   }}</pre>
 *
 * <h3>Number Handling</h3>
 * This reader permits numeric values to be read as strings and string values to
 * be read as numbers. For example, both elements of the JSON array {@code
 * [1, "1"]} may be read using either {@link #nextInt} or {@link #nextString}.
 * This behavior is intended to prevent lossy numeric conversions: double is
 * JavaScript's only numeric type and very large values like {@code
 * 9007199254740993} cannot be represented exactly on that platform. To minimize
 * precision loss, extremely large values should be written and read as strings
 * in JSON.
 *
 * <a id="nonexecuteprefix"/><h3>Non-Execute Prefix</h3>
 * Web servers that serve private data using JSON may be vulnerable to <a
 * href="http://en.wikipedia.org/wiki/JSON#Cross-site_request_forgery">Cross-site
 * request forgery</a> attacks. In such an attack, a malicious site gains access
 * to a private JSON file by executing it with an HTML {@code <script>} tag.
 *
 * <p>Prefixing JSON files with <code>")]}'\n"</code> makes them non-executable
 * by {@code <script>} tags, disarming the attack. Since the prefix is malformed
 * JSON, strict parsing fails when it is encountered. This class permits the
 * non-execute prefix when {@link #setLenient(boolean) lenient parsing} is
 * enabled.
 *
 * <p>Each {@code JsonReader} may be used to read a single JSON stream. Instances
 * of this class are not thread safe.
 *
 * @author Jesse Wilson
 * @since 1.6
 */
/*#1 is inserting the first element into the stack, stackSize is 1 which is less than length of stackSize
and is equal to the number of elements in stack, therefore is valid and safe (Could not add SuppressWarnings to instance initializer block)*/
@SuppressWarnings("unary.increment.type.incompatible")
public class JsonReader implements Closeable {
  private static final long MIN_INCOMPLETE_INTEGER = Long.MIN_VALUE / 10;

  private static final int PEEKED_NONE = 0;
  private static final int PEEKED_BEGIN_OBJECT = 1;
  private static final int PEEKED_END_OBJECT = 2;
  private static final int PEEKED_BEGIN_ARRAY = 3;
  private static final int PEEKED_END_ARRAY = 4;
  private static final int PEEKED_TRUE = 5;
  private static final int PEEKED_FALSE = 6;
  private static final int PEEKED_NULL = 7;
  private static final int PEEKED_SINGLE_QUOTED = 8;
  private static final int PEEKED_DOUBLE_QUOTED = 9;
  private static final int PEEKED_UNQUOTED = 10;
  /** When this is returned, the string value is stored in peekedString. */
  private static final int PEEKED_BUFFERED = 11;
  private static final int PEEKED_SINGLE_QUOTED_NAME = 12;
  private static final int PEEKED_DOUBLE_QUOTED_NAME = 13;
  private static final int PEEKED_UNQUOTED_NAME = 14;
  /** When this is returned, the integer value is stored in peekedLong. */
  private static final int PEEKED_LONG = 15;
  private static final int PEEKED_NUMBER = 16;
  private static final int PEEKED_EOF = 17;

  /* State machine when parsing numbers */
  private static final int NUMBER_CHAR_NONE = 0;
  private static final int NUMBER_CHAR_SIGN = 1;
  private static final int NUMBER_CHAR_DIGIT = 2;
  private static final int NUMBER_CHAR_DECIMAL = 3;
  private static final int NUMBER_CHAR_FRACTION_DIGIT = 4;
  private static final int NUMBER_CHAR_EXP_E = 5;
  private static final int NUMBER_CHAR_EXP_SIGN = 6;
  private static final int NUMBER_CHAR_EXP_DIGIT = 7;

  /** The input JSON. */
  private final Reader in;

  /** True to accept non-spec compliant JSON */
  private boolean lenient = false;

  /**
   * Use a manual buffer to easily read and unread upcoming characters, and
   * also so we can create strings without an intermediate StringBuilder.
   * We decode literals directly out of this buffer, so it must be at least as
   * long as the longest token that can be reported as a number.
   */
  private final char[] buffer = new char[1024];
  private @NonNegative @LTLengthOf(value="buffer") int pos = 0;
  private @NonNegative @LTLengthOf("buffer") int limit = 0;

  private int lineNumber = 0;
  private int lineStart = 0;

  int peeked = PEEKED_NONE;

  /**
   * A peeked value that was composed entirely of digits with an optional
   * leading dash. Positive values may not have a leading 0.
   */
  private long peekedLong;

  /**
   * The number of characters in a peeked number literal. Increment 'pos' by
   * this after reading a number.
   */
  private @NonNegative @LTLengthOf(value="this.buffer", offset="this.pos-1") int peekedNumberLength;

  /**
   * A peeked string that should be parsed on the next double, long or string.
   * This is populated before a numeric value is parsed and used if that parsing
   * fails.
   */
  private String peekedString;

  /*
   * The nesting stack. Using a manual array rather than an ArrayList saves 20%.
   */
  private int[] stack = new int[32];
  private @NonNegative @LTEqLengthOf({"stack", "pathNames", "pathIndices"}) int stackSize = 0;
  {
    stack[stackSize++] = JsonScope.EMPTY_DOCUMENT; //#1
  }

  /*
   * The path members. It corresponds directly to stack: At indices where the
   * stack contains an object (EMPTY_OBJECT, DANGLING_NAME or NONEMPTY_OBJECT),
   * pathNames contains the name at this scope. Where it contains an array
   * (EMPTY_ARRAY, NONEMPTY_ARRAY) pathIndices contains the current index in
   * that array. Otherwise the value is undefined, and we take advantage of that
   * by incrementing pathIndices when doing so isn't useful.
   */
  private String[] pathNames = new String[32];
  private int[] pathIndices = new int[32];

  /**
   * Creates a new instance that reads a JSON-encoded stream from {@code in}.
   */
  public JsonReader(Reader in) {
    if (in == null) {
      throw new NullPointerException("in == null");
    }
    this.in = in;
  }

  /**
   * Configure this parser to be liberal in what it accepts. By default,
   * this parser is strict and only accepts JSON as specified by <a
   * href="http://www.ietf.org/rfc/rfc4627.txt">RFC 4627</a>. Setting the
   * parser to lenient causes it to ignore the following syntax errors:
   *
   * <ul>
   *   <li>Streams that start with the <a href="#nonexecuteprefix">non-execute
   *       prefix</a>, <code>")]}'\n"</code>.
   *   <li>Streams that include multiple top-level values. With strict parsing,
   *       each stream must contain exactly one top-level value.
   *   <li>Top-level values of any type. With strict parsing, the top-level
   *       value must be an object or an array.
   *   <li>Numbers may be {@link Double#isNaN() NaNs} or {@link
   *       Double#isInfinite() infinities}.
   *   <li>End of line comments starting with {@code //} or {@code #} and
   *       ending with a newline character.
   *   <li>C-style comments starting with {@code /*} and ending with
   *       {@code *}{@code /}. Such comments may not be nested.
   *   <li>Names that are unquoted or {@code 'single quoted'}.
   *   <li>Strings that are unquoted or {@code 'single quoted'}.
   *   <li>Array elements separated by {@code ;} instead of {@code ,}.
   *   <li>Unnecessary array separators. These are interpreted as if null
   *       was the omitted value.
   *   <li>Names and values separated by {@code =} or {@code =>} instead of
   *       {@code :}.
   *   <li>Name/value pairs separated by {@code ;} instead of {@code ,}.
   * </ul>
   */
  public final void setLenient(boolean lenient) {
    this.lenient = lenient;
  }

  /**
   * Returns true if this parser is liberal in what it accepts.
   */
  public final boolean isLenient() {
    return lenient;
  }

  /**
   * Consumes the next token from the JSON stream and asserts that it is the
   * beginning of a new array.
   */
  /*push #2 ensures the stackSize>1 therefore stackSize - 1 #3 is a safe index*/
  @SuppressWarnings("array.access.unsafe.low")
  public void beginArray() throws IOException {
    int p = peeked;
    if (p == PEEKED_NONE) {
      p = doPeek();
    }
    if (p == PEEKED_BEGIN_ARRAY) {
      push(JsonScope.EMPTY_ARRAY); //#2
      pathIndices[stackSize - 1] = 0; //#3
      peeked = PEEKED_NONE;
    } else {
      throw new IllegalStateException("Expected BEGIN_ARRAY but was " + peek() + locationString());
    }
  }

  /**
   * Consumes the next token from the JSON stream and asserts that it is the
   * end of the current array.
   */
  /*if #4 condition is true, there are more than two elements in the stack
  pushed by the instance initializer block and by beginArray(),
  therefore #5 and #6 are safe*/
  @SuppressWarnings({"array.access.unsafe.low","unary.decrement.type.incompatible"})
  public void endArray() throws IOException {
    int p = peeked;
    if (p == PEEKED_NONE) {
      p = doPeek();
    }
    if (p == PEEKED_END_ARRAY) { //#4
      stackSize--; //#5
      pathIndices[stackSize - 1]++; //#6
      peeked = PEEKED_NONE;
    } else {
      throw new IllegalStateException("Expected END_ARRAY but was " + peek() + locationString());
    }
  }

  /**
   * Consumes the next token from the JSON stream and asserts that it is the
   * beginning of a new object.
   */
  public void beginObject() throws IOException {
    int p = peeked;
    if (p == PEEKED_NONE) {
      p = doPeek();
    }
    if (p == PEEKED_BEGIN_OBJECT) {
      push(JsonScope.EMPTY_OBJECT);
      peeked = PEEKED_NONE;
    } else {
      throw new IllegalStateException("Expected BEGIN_OBJECT but was " + peek() + locationString());
    }
  }

  /**
   * Consumes the next token from the JSON stream and asserts that it is the
   * end of the current object.
   */
   /*if #7 condition is true, there are more than two elements in the stack,
   pushed by the instance initializer block and by beginObject(),
   therefore #8 and #9 are safe*/
  @SuppressWarnings({"array.access.unsafe.low","unary.decrement.type.incompatible"})
  public void endObject() throws IOException {
    int p = peeked;
    if (p == PEEKED_NONE) {
      p = doPeek();
    }
    if (p == PEEKED_END_OBJECT) { //#7
      stackSize--; //#8
      pathNames[stackSize] = null; // Free the last path name so that it can be garbage collected!
      pathIndices[stackSize - 1]++; //#9
      peeked = PEEKED_NONE;
    } else {
      throw new IllegalStateException("Expected END_OBJECT but was " + peek() + locationString());
    }
  }

  /**
   * Returns true if the current array or object has another element.
   */
  public boolean hasNext() throws IOException {
    int p = peeked;
    if (p == PEEKED_NONE) {
      p = doPeek();
    }
    return p != PEEKED_END_OBJECT && p != PEEKED_END_ARRAY;
  }

  /**
   * Returns the type of the next token without consuming it.
   */
  public JsonToken peek() throws IOException {
    int p = peeked;
    if (p == PEEKED_NONE) {
      p = doPeek();
    }

    switch (p) {
    case PEEKED_BEGIN_OBJECT:
      return JsonToken.BEGIN_OBJECT;
    case PEEKED_END_OBJECT:
      return JsonToken.END_OBJECT;
    case PEEKED_BEGIN_ARRAY:
      return JsonToken.BEGIN_ARRAY;
    case PEEKED_END_ARRAY:
      return JsonToken.END_ARRAY;
    case PEEKED_SINGLE_QUOTED_NAME:
    case PEEKED_DOUBLE_QUOTED_NAME:
    case PEEKED_UNQUOTED_NAME:
      return JsonToken.NAME;
    case PEEKED_TRUE:
    case PEEKED_FALSE:
      return JsonToken.BOOLEAN;
    case PEEKED_NULL:
      return JsonToken.NULL;
    case PEEKED_SINGLE_QUOTED:
    case PEEKED_DOUBLE_QUOTED:
    case PEEKED_UNQUOTED:
    case PEEKED_BUFFERED:
      return JsonToken.STRING;
    case PEEKED_LONG:
    case PEEKED_NUMBER:
      return JsonToken.NUMBER;
    case PEEKED_EOF:
      return JsonToken.END_DOCUMENT;
    default:
      throw new AssertionError();
    }
  }

  /*the stack contains atleast one elemnent pushed by the instance initializer block
  therefore #10 is safe*/
  /*pos > 0 as atleast one character is read, therefore #11 #14 #15 are safe*/
  /*pos is ensured to be less than limit #12 therefore pos++ #13 is safe*/
  @SuppressWarnings({"array.access.unsafe.low","unary.decrement.type.incompatible","unary.increment.type.incompatible"})
  int doPeek() throws IOException {
    int peekStack = stack[stackSize - 1]; //#10
    if (peekStack == JsonScope.EMPTY_ARRAY) {
      stack[stackSize - 1] = JsonScope.NONEMPTY_ARRAY;
    } else if (peekStack == JsonScope.NONEMPTY_ARRAY) {
      // Look for a comma before the next element.
      int c = nextNonWhitespace(true);
      switch (c) {
      case ']':
        return peeked = PEEKED_END_ARRAY;
      case ';':
        checkLenient(); // fall-through
      case ',':
        break;
      default:
        throw syntaxError("Unterminated array");
      }
    } else if (peekStack == JsonScope.EMPTY_OBJECT || peekStack == JsonScope.NONEMPTY_OBJECT) {
      stack[stackSize - 1] = JsonScope.DANGLING_NAME;
      // Look for a comma before the next element.
      if (peekStack == JsonScope.NONEMPTY_OBJECT) {
        int c = nextNonWhitespace(true);
        switch (c) {
        case '}':
          return peeked = PEEKED_END_OBJECT;
        case ';':
          checkLenient(); // fall-through
        case ',':
          break;
        default:
          throw syntaxError("Unterminated object");
        }
      }
      int c = nextNonWhitespace(true);
      switch (c) {
      case '"':
        return peeked = PEEKED_DOUBLE_QUOTED_NAME;
      case '\'':
        checkLenient();
        return peeked = PEEKED_SINGLE_QUOTED_NAME;
      case '}':
        if (peekStack != JsonScope.NONEMPTY_OBJECT) {
          return peeked = PEEKED_END_OBJECT;
        } else {
          throw syntaxError("Expected name");
        }
      default:
        checkLenient();
        pos--; // Don't consume the first character in an unquoted string //#11
        if (isLiteral((char) c)) {
          return peeked = PEEKED_UNQUOTED_NAME;
        } else {
          throw syntaxError("Expected name");
        }
      }
    } else if (peekStack == JsonScope.DANGLING_NAME) {
      stack[stackSize - 1] = JsonScope.NONEMPTY_OBJECT;
      // Look for a colon before the value.
      int c = nextNonWhitespace(true);
      switch (c) {
      case ':':
        break;
      case '=':
        checkLenient();
        if ((pos < limit || fillBuffer(1)) && buffer[pos] == '>') { //#12
          pos++; //#13
        }
        break;
      default:
        throw syntaxError("Expected ':'");
      }
    } else if (peekStack == JsonScope.EMPTY_DOCUMENT) {
      if (lenient) {
        consumeNonExecutePrefix();
      }
      stack[stackSize - 1] = JsonScope.NONEMPTY_DOCUMENT;
    } else if (peekStack == JsonScope.NONEMPTY_DOCUMENT) {
      int c = nextNonWhitespace(false);
      if (c == -1) {
        return peeked = PEEKED_EOF;
      } else {
        checkLenient();
        pos--; //#14
      }
    } else if (peekStack == JsonScope.CLOSED) {
      throw new IllegalStateException("JsonReader is closed");
    }

    int c = nextNonWhitespace(true);
    switch (c) {
    case ']':
      if (peekStack == JsonScope.EMPTY_ARRAY) {
        return peeked = PEEKED_END_ARRAY;
      }
      // fall-through to handle ",]"
    case ';':
    case ',':
      // In lenient mode, a 0-length literal in an array means 'null'.
      if (peekStack == JsonScope.EMPTY_ARRAY || peekStack == JsonScope.NONEMPTY_ARRAY) {
        checkLenient();
        pos--; //#15
        return peeked = PEEKED_NULL;
      } else {
        throw syntaxError("Unexpected value");
      }
    case '\'':
      checkLenient();
      return peeked = PEEKED_SINGLE_QUOTED;
    case '"':
      return peeked = PEEKED_DOUBLE_QUOTED;
    case '[':
      return peeked = PEEKED_BEGIN_ARRAY;
    case '{':
      return peeked = PEEKED_BEGIN_OBJECT;
    default:
      pos--; // Don't consume the first character in a literal value.
    }
    int result = peekKeyword();
    if (result != PEEKED_NONE) {
      return result;
    }

    result = peekNumber();
    if (result != PEEKED_NONE) {
      return result;
    }

    if (!isLiteral(buffer[pos])) {
      throw syntaxError("Expected value");
    }

    checkLenient();
    return peeked = PEEKED_UNQUOTED;
  }

  /*if statement check if pos + i is less than limit #17 therefore #18 is safe*/
  /*loop #16 is valid traversal for both keyword and keywordUpper as they have same length,
  therefore incrementing i in the loop leads to no unsafe access #19*/
  /*According to documentation buffer is of highest length required for any token,
  therefore pos+length #20 is less that buffer.length.*/
  @SuppressWarnings({"array.access.unsafe.high","unary.increment.type.incompatible","compound.assignment.type.incompatible"})
  private int peekKeyword() throws IOException {
    // Figure out which keyword we're matching against by its first character.
    char c = buffer[pos];
    String keyword;
    String keywordUpper;
    int peeking;
    if (c == 't' || c == 'T') {
      keyword = "true";
      keywordUpper = "TRUE";
      peeking = PEEKED_TRUE;
    } else if (c == 'f' || c == 'F') {
      keyword = "false";
      keywordUpper = "FALSE";
      peeking = PEEKED_FALSE;
    } else if (c == 'n' || c == 'N') {
      keyword = "null";
      keywordUpper = "NULL";
      peeking = PEEKED_NULL;
    } else {
      return PEEKED_NONE;
    }

    // Confirm that chars [1..length) match the keyword.
    int length = keyword.length();
    for (@IndexFor({"keyword","keywordUpper"}) int i = 1; i < length; i++) {//#16
      if (pos + i >= limit && !fillBuffer(i + 1)) { //#17
        return PEEKED_NONE;
      }
      c = buffer[pos + i]; //#18
      if (c != keyword.charAt(i) && c != keywordUpper.charAt(i)) { //#19
        return PEEKED_NONE;
      }
    }

    if ((pos + length < limit || fillBuffer(length + 1))
        && isLiteral(buffer[pos + length])) {
      return PEEKED_NONE; // Don't match trues, falsey or nullsoft!
    }

    // We've found the keyword followed either by EOF or by a non-literal character.
    pos += length; //#20
    return peeked = peeking;
  }

  /*According to documentation buffer is of highest length required for any token,
  therefore pos+i #24 is less that buffer.length.*/
  /*#21 ensures p+i < limit, therefore p+i is a valid index for buffer #23*/
  /*#22 ensures i < buffer.length therefore #25 is safe*/
  @SuppressWarnings({"compound.assignment.type.incompatible","array.access.unsafe.high","assignment.type.incompatible"})
  private int peekNumber() throws IOException {
    // Like nextNonWhitespace, this uses locals 'p' and 'l' to save inner-loop field access.
    char[] buffer = this.buffer;
    int p = pos;
    int l = limit;

    long value = 0; // Negative to accommodate Long.MIN_VALUE more easily.
    boolean negative = false;
    boolean fitsInLong = true;
    int last = NUMBER_CHAR_NONE;

    @NonNegative int i = 0;

    charactersOfNumber:
    for (; true; i++) {
      if (p + i == l) { //#21
        if (i == buffer.length) { //#22
          // Though this looks like a well-formed number, it's too long to continue reading. Give up
          // and let the application handle this as an unquoted literal.
          return PEEKED_NONE;
        }
        if (!fillBuffer(i + 1)) {
          break;
        }
        p = pos;
        l = limit;
      }

      char c = buffer[p + i]; //#23
      switch (c) {
      case '-':
        if (last == NUMBER_CHAR_NONE) {
          negative = true;
          last = NUMBER_CHAR_SIGN;
          continue;
        } else if (last == NUMBER_CHAR_EXP_E) {
          last = NUMBER_CHAR_EXP_SIGN;
          continue;
        }
        return PEEKED_NONE;

      case '+':
        if (last == NUMBER_CHAR_EXP_E) {
          last = NUMBER_CHAR_EXP_SIGN;
          continue;
        }
        return PEEKED_NONE;

      case 'e':
      case 'E':
        if (last == NUMBER_CHAR_DIGIT || last == NUMBER_CHAR_FRACTION_DIGIT) {
          last = NUMBER_CHAR_EXP_E;
          continue;
        }
        return PEEKED_NONE;

      case '.':
        if (last == NUMBER_CHAR_DIGIT) {
          last = NUMBER_CHAR_DECIMAL;
          continue;
        }
        return PEEKED_NONE;

      default:
        if (c < '0' || c > '9') {
          if (!isLiteral(c)) {
            break charactersOfNumber;
          }
          return PEEKED_NONE;
        }
        if (last == NUMBER_CHAR_SIGN || last == NUMBER_CHAR_NONE) {
          value = -(c - '0');
          last = NUMBER_CHAR_DIGIT;
        } else if (last == NUMBER_CHAR_DIGIT) {
          if (value == 0) {
            return PEEKED_NONE; // Leading '0' prefix is not allowed (since it could be octal).
          }
          long newValue = value * 10 - (c - '0');
          fitsInLong &= value > MIN_INCOMPLETE_INTEGER
              || (value == MIN_INCOMPLETE_INTEGER && newValue < value);
          value = newValue;
        } else if (last == NUMBER_CHAR_DECIMAL) {
          last = NUMBER_CHAR_FRACTION_DIGIT;
        } else if (last == NUMBER_CHAR_EXP_E || last == NUMBER_CHAR_EXP_SIGN) {
          last = NUMBER_CHAR_EXP_DIGIT;
        }
      }
    }

    // We've read a complete number. Decide if it's a PEEKED_LONG or a PEEKED_NUMBER.
    if (last == NUMBER_CHAR_DIGIT && fitsInLong && (value != Long.MIN_VALUE || negative) && (value!=0 || false==negative)) {
      peekedLong = negative ? value : -value;
      pos += i; //#24
      return peeked = PEEKED_LONG;
    } else if (last == NUMBER_CHAR_DIGIT || last == NUMBER_CHAR_FRACTION_DIGIT
        || last == NUMBER_CHAR_EXP_DIGIT) {
      peekedNumberLength = i; //#25
      return peeked = PEEKED_NUMBER;
    } else {
      return PEEKED_NONE;
    }
  }

  private boolean isLiteral(char c) throws IOException {
    switch (c) {
    case '/':
    case '\\':
    case ';':
    case '#':
    case '=':
      checkLenient(); // fall-through
    case '{':
    case '}':
    case '[':
    case ']':
    case ':':
    case ',':
    case ' ':
    case '\t':
    case '\f':
    case '\r':
    case '\n':
      return false;
    default:
      return true;
    }
  }

  /**
   * Returns the next token, a {@link com.google.gson.stream.JsonToken#NAME property name}, and
   * consumes it.
   *
   * @throws java.io.IOException if the next token in the stream is not a property
   *     name.
   */
  /*#26 doPeek() returns the top of the stack, if it does not return one of the
  expected values, exception is thrown therefore stackSize > 1 and #27 is safe*/
  @SuppressWarnings("array.access.unsafe.low")
  public String nextName() throws IOException {
    int p = peeked;
    if (p == PEEKED_NONE) {
      p = doPeek(); //#26
    }
    String result;
    if (p == PEEKED_UNQUOTED_NAME) {
      result = nextUnquotedValue();
    } else if (p == PEEKED_SINGLE_QUOTED_NAME) {
      result = nextQuotedValue('\'');
    } else if (p == PEEKED_DOUBLE_QUOTED_NAME) {
      result = nextQuotedValue('"');
    } else {
      throw new IllegalStateException("Expected a name but was " + peek() + locationString());
    }
    peeked = PEEKED_NONE;
    pathNames[stackSize - 1] = result; //#27
    return result;
  }

  /**
   * Returns the {@link com.google.gson.stream.JsonToken#STRING string} value of the next token,
   * consuming it. If the next token is a number, this method will return its
   * string form.
   *
   * @throws IllegalStateException if the next token is not a string or if
   *     this reader is closed.
   */
   /*#28 doPeek() returns the top of the stack, if it does not return one of the
   expected values, exception is thrown therefore stackSize > 1 and #30 is safe*/
   /*According to documentation buffer is of highest length required for any token,
   therefore pos+peekedNumberLength #29 is less that buffer.length.*/
  @SuppressWarnings({"array.access.unsafe.low","compound.assignment.type.incompatible"})
  public String nextString() throws IOException {
    int p = peeked;
    if (p == PEEKED_NONE) {
      p = doPeek(); //#28
    }
    String result;
    if (p == PEEKED_UNQUOTED) {
      result = nextUnquotedValue();
    } else if (p == PEEKED_SINGLE_QUOTED) {
      result = nextQuotedValue('\'');
    } else if (p == PEEKED_DOUBLE_QUOTED) {
      result = nextQuotedValue('"');
    } else if (p == PEEKED_BUFFERED) {
      result = peekedString;
      peekedString = null;
    } else if (p == PEEKED_LONG) {
      result = Long.toString(peekedLong);
    } else if (p == PEEKED_NUMBER) {
      result = new String(buffer, pos, peekedNumberLength);
      pos += peekedNumberLength; //#29
    } else {
      throw new IllegalStateException("Expected a string but was " + peek() + locationString());
    }
    peeked = PEEKED_NONE;
    pathIndices[stackSize - 1]++; //#30
    return result;
  }

  /**
   * Returns the {@link com.google.gson.stream.JsonToken#BOOLEAN boolean} value of the next token,
   * consuming it.
   *
   * @throws IllegalStateException if the next token is not a boolean or if
   *     this reader is closed.
   */
   /*#31 doPeek() returns the top of the stack, if it does not return one of the
   expected values, exception is thrown therefore stackSize > 1 and #32 #33 is safe*/
  @SuppressWarnings("array.access.unsafe.low")
  public boolean nextBoolean() throws IOException {
    int p = peeked;
    if (p == PEEKED_NONE) {
      p = doPeek(); //#31
    }
    if (p == PEEKED_TRUE) {
      peeked = PEEKED_NONE;
      pathIndices[stackSize - 1]++; //#32
      return true;
    } else if (p == PEEKED_FALSE) {
      peeked = PEEKED_NONE;
      pathIndices[stackSize - 1]++; //#33
      return false;
    }
    throw new IllegalStateException("Expected a boolean but was " + peek() + locationString());
  }

  /**
   * Consumes the next token from the JSON stream and asserts that it is a
   * literal null.
   *
   * @throws IllegalStateException if the next token is not null or if this
   *     reader is closed.
   */
   /*#34 doPeek() returns the top of the stack, if it does not return one of the
   expected values, exception is thrown therefore stackSize > 1 and #35 is safe*/
  @SuppressWarnings("array.access.unsafe.low")
  public void nextNull() throws IOException {
    int p = peeked;
    if (p == PEEKED_NONE) {
      p = doPeek(); //#34
    }
    if (p == PEEKED_NULL) {
      peeked = PEEKED_NONE;
      pathIndices[stackSize - 1]++; //#35
    } else {
      throw new IllegalStateException("Expected null but was " + peek() + locationString());
    }
  }

  /**
   * Returns the {@link com.google.gson.stream.JsonToken#NUMBER double} value of the next token,
   * consuming it. If the next token is a string, this method will attempt to
   * parse it as a double using {@link Double#parseDouble(String)}.
   *
   * @throws IllegalStateException if the next token is not a literal value.
   * @throws NumberFormatException if the next literal value cannot be parsed
   *     as a double, or is non-finite.
   */
   /*#36 doPeek() returns the top of the stack, if it does not return one of the
   expected values, exception is thrown therefore stackSize > 1 and #37 #39 are safe*/
  /* According to documentatiom buffer is taken for highest length token,
  pos + peekedNuberLength < buffer.length and safe #38*/
  @SuppressWarnings({"array.access.unsafe.low","compound.assignment.type.incompatible"})
  public double nextDouble() throws IOException {
    int p = peeked;
    if (p == PEEKED_NONE) {
      p = doPeek(); //#36
    }

    if (p == PEEKED_LONG) {
      peeked = PEEKED_NONE;
      pathIndices[stackSize - 1]++; //#37
      return (double) peekedLong;
    }

    if (p == PEEKED_NUMBER) {
      peekedString = new String(buffer, pos, peekedNumberLength);
      pos += peekedNumberLength; //#38
    } else if (p == PEEKED_SINGLE_QUOTED || p == PEEKED_DOUBLE_QUOTED) {
      peekedString = nextQuotedValue(p == PEEKED_SINGLE_QUOTED ? '\'' : '"');
    } else if (p == PEEKED_UNQUOTED) {
      peekedString = nextUnquotedValue();
    } else if (p != PEEKED_BUFFERED) {
      throw new IllegalStateException("Expected a double but was " + peek() + locationString());
    }

    peeked = PEEKED_BUFFERED;
    double result = Double.parseDouble(peekedString); // don't catch this NumberFormatException.
    if (!lenient && (Double.isNaN(result) || Double.isInfinite(result))) {
      throw new MalformedJsonException(
          "JSON forbids NaN and infinities: " + result + locationString());
    }
    peekedString = null;
    peeked = PEEKED_NONE;
    pathIndices[stackSize - 1]++; //#39
    return result;
  }

  /**
   * Returns the {@link com.google.gson.stream.JsonToken#NUMBER long} value of the next token,
   * consuming it. If the next token is a string, this method will attempt to
   * parse it as a long. If the next token's numeric value cannot be exactly
   * represented by a Java {@code long}, this method throws.
   *
   * @throws IllegalStateException if the next token is not a literal value.
   * @throws NumberFormatException if the next literal value cannot be parsed
   *     as a number, or exactly represented as a long.
   */
   /*#40 doPeek() returns the top of the stack, if it does not return one of the
   expected values, exception is thrown therefore stackSize > 1 and #41 #43  #44 are safe*/
  /* According to documentatiom buffer is taken for highest length token,
  pos + peekedNuberLength < buffer.length and is safe #42*/
  @SuppressWarnings({"array.access.unsafe.low","compound.assignment.type.incompatible"})
  public long nextLong() throws IOException {
    int p = peeked;
    if (p == PEEKED_NONE) {
      p = doPeek(); //#40
    }

    if (p == PEEKED_LONG) {
      peeked = PEEKED_NONE;
      pathIndices[stackSize - 1]++; //#41
      return peekedLong;
    }

    if (p == PEEKED_NUMBER) {
      peekedString = new String(buffer, pos, peekedNumberLength);
      pos += peekedNumberLength; //#42
    } else if (p == PEEKED_SINGLE_QUOTED || p == PEEKED_DOUBLE_QUOTED || p == PEEKED_UNQUOTED) {
      if (p == PEEKED_UNQUOTED) {
        peekedString = nextUnquotedValue();
      } else {
        peekedString = nextQuotedValue(p == PEEKED_SINGLE_QUOTED ? '\'' : '"');
      }
      try {
        long result = Long.parseLong(peekedString);
        peeked = PEEKED_NONE;
        pathIndices[stackSize - 1]++; //#43
        return result;
      } catch (NumberFormatException ignored) {
        // Fall back to parse as a double below.
      }
    } else {
      throw new IllegalStateException("Expected a long but was " + peek() + locationString());
    }

    peeked = PEEKED_BUFFERED;
    double asDouble = Double.parseDouble(peekedString); // don't catch this NumberFormatException.
    long result = (long) asDouble;
    if (result != asDouble) { // Make sure no precision was lost casting to 'long'.
      throw new NumberFormatException("Expected a long but was " + peekedString + locationString());
    }
    peekedString = null;
    peeked = PEEKED_NONE;
    pathIndices[stackSize - 1]++; //#44
    return result;
  }

  /**
   * Returns the string up to but not including {@code quote}, unescaping any
   * character escape sequences encountered along the way. The opening quote
   * should have already been read. This consumes the closing quote, but does
   * not include it in the returned string.
   *
   * @param quote either ' or ".
   * @throws NumberFormatException if any unicode escape sequences are
   *     malformed.
   */
  //p > start due to #46, p - start > 0 and less than buffer.length - start, #47 #48 are safe
  //as p > start, p-start is a valid argument #49
  @SuppressWarnings({"assignment.type.incompatible","argument.type.incompatible"})
  private String nextQuotedValue(char quote) throws IOException {
    // Like nextNonWhitespace, this uses locals 'p' and 'l' to save inner-loop field access.
    char[] buffer = this.buffer;
    StringBuilder builder = null;
    while (true) {
      @NonNegative @LTLengthOf("buffer") int p = pos;
      int l = limit;
      /* the index of the first character not yet appended to the builder. */
      int start = p;
      while (p < l) { //#45
        //The while condition #45 ensures p < l and less than buffer.length, #46 is safe
       @SuppressWarnings({"array.access.unsafe.high","unary.increment.type.incompatible"})
        int c = buffer[p++]; //#46

        if (c == quote) {
          pos = p;
          @NonNegative @LTLengthOf(value="buffer", offset="start - 1") int len = p - start - 1; //#47
          if (builder == null) {
            return new String(buffer, start, len);
          } else {
            builder.append(buffer, start, len);
            return builder.toString();
          }
        } else if (c == '\\') {
          pos = p;
          @NonNegative @LTLengthOf(value="buffer", offset="start - 1") int len = p - start - 1; //#48
          if (builder == null) {
            int estimatedLength = (len + 1) * 2;
            builder = new StringBuilder(Math.max(estimatedLength, 16));
          }
          builder.append(buffer, start, len);
          builder.append(readEscapeCharacter());
          p = pos;
          l = limit;
          start = p;
        } else if (c == '\n') {
          lineNumber++;
          lineStart = p;
        }
      }

      if (builder == null) {
        int estimatedLength = (p - start) * 2;
        builder = new StringBuilder(Math.max(estimatedLength, 16));
      }
      builder.append(buffer, start, p - start); //#49
      pos = p;
      if (!fillBuffer(1)) {
        throw syntaxError("Unterminated string");
      }
    }
  }

  /**
   * Returns an unquoted value as a string.
   */
   /* According to documentatiom buffer is taken for highest length token,
   therefore pos + i is less than buffer.length #52 #54*/
   /*i = 0 is valid as pos could have a maximum value of buffer.length - 1 #50 #53*/
   /* #51 is safe as the condition ensures that i < limit - pos in the loop*/
  @SuppressWarnings({"fallthrough","compound.assignment.type.incompatible","assignment.type.incompatible","unary.increment.type.incompatible"})
  private String nextUnquotedValue() throws IOException {
    StringBuilder builder = null;
    @NonNegative @LTLengthOf(value="buffer", offset="this.pos-1") int i = 0; //#50

    findNonLiteralCharacter:
    while (true) {
      for (; pos + i < limit; i++) { //51
        switch (buffer[pos + i]) {
        case '/':
        case '\\':
        case ';':
        case '#':
        case '=':
          checkLenient(); // fall-through
        case '{':
        case '}':
        case '[':
        case ']':
        case ':':
        case ',':
        case ' ':
        case '\t':
        case '\f':
        case '\r':
        case '\n':
          break findNonLiteralCharacter;
        }
      }

      // Attempt to load the entire literal into the buffer at once.
      if (i < buffer.length) {
        if (fillBuffer(i + 1)) {
          continue;
        } else {
          break;
        }
      }

      // use a StringBuilder when the value is too long. This is too long to be a number!
      if (builder == null) {
        builder = new StringBuilder(Math.max(i,16));
      }
      builder.append(buffer, pos, i);
      pos += i; //52
      i = 0; //#53
      if (!fillBuffer(1)) {
        break;
      }
    }

    String result = (null == builder) ? new String(buffer, pos, i) : builder.append(buffer, pos, i).toString();
    pos += i; //#54
    return result;
  }

  private void skipQuotedValue(char quote) throws IOException {
    // Like nextNonWhitespace, this uses locals 'p' and 'l' to save inner-loop field access.
    char[] buffer = this.buffer;
    do {
      int p = pos;
      int l = limit;
      /* the index of the first character not yet appended to the builder. */
      while (p < l) { //#55
        //The abouve while #55 ensure p < l and buffer.length
			  @SuppressWarnings("array.access.unsafe.high")
        int c = buffer[p++];
        if (c == quote) {
          pos = p;
          return;
        } else if (c == '\\') {
          pos = p;
          readEscapeCharacter();
          p = pos;
          l = limit;
        } else if (c == '\n') {
          lineNumber++;
          lineStart = p;
        }
      }
      pos = p;
    } while (fillBuffer(1));
    throw syntaxError("Unterminated string");
  }

  /* According to documentatiom buffer is taken for highest length token,
  therefore pos + i is less than buffer.length #56*/
  @SuppressWarnings("compound.assignment.type.incompatible")
  private void skipUnquotedValue() throws IOException {
    do {
      int i = 0;
      for (; pos + i < limit; i++) {
        switch (buffer[pos + i]) {
        case '/':
        case '\\':
        case ';':
        case '#':
        case '=':
          checkLenient(); // fall-through
        case '{':
        case '}':
        case '[':
        case ']':
        case ':':
        case ',':
        case ' ':
        case '\t':
        case '\f':
        case '\r':
        case '\n':
          pos += i;
          return;
        }
      }
      pos += i; //#56
    } while (fillBuffer(1));
  }

  /**
   * Returns the {@link com.google.gson.stream.JsonToken#NUMBER int} value of the next token,
   * consuming it. If the next token is a string, this method will attempt to
   * parse it as an int. If the next token's numeric value cannot be exactly
   * represented by a Java {@code int}, this method throws.
   *
   * @throws IllegalStateException if the next token is not a literal value.
   * @throws NumberFormatException if the next literal value cannot be parsed
   *     as a number, or exactly represented as an int.
   */
   /*#57 doPeek() returns the top of the stack, if it does not return one of the
   expected values, exception is thrown therefore stackSize > 1 and #58 #60 #61 are safe*/
   /* According to documentatiom buffer is taken for highest length token,
   pos + peekedNuberLength < buffer.length and is safe #59*/
   @SuppressWarnings({"array.access.unsafe.low","compound.assignment.type.incompatible"})
  public int nextInt() throws IOException {
    int p = peeked;
    if (p == PEEKED_NONE) {
      p = doPeek(); //#57
    }

    int result;
    if (p == PEEKED_LONG) {
      result = (int) peekedLong;
      if (peekedLong != result) { // Make sure no precision was lost casting to 'int'.
        throw new NumberFormatException("Expected an int but was " + peekedLong + locationString());
      }
      peeked = PEEKED_NONE;
      pathIndices[stackSize - 1]++; //#58
      return result;
    }

    if (p == PEEKED_NUMBER) {
      peekedString = new String(buffer, pos, peekedNumberLength);
      pos += peekedNumberLength; //#59
    } else if (p == PEEKED_SINGLE_QUOTED || p == PEEKED_DOUBLE_QUOTED || p == PEEKED_UNQUOTED) {
      if (p == PEEKED_UNQUOTED) {
        peekedString = nextUnquotedValue();
      } else {
        peekedString = nextQuotedValue(p == PEEKED_SINGLE_QUOTED ? '\'' : '"');
      }
      try {
        result = Integer.parseInt(peekedString);
        peeked = PEEKED_NONE;
        pathIndices[stackSize - 1]++; //#60
        return result;
      } catch (NumberFormatException ignored) {
        // Fall back to parse as a double below.
      }
    } else {
      throw new IllegalStateException("Expected an int but was " + peek() + locationString());
    }

    peeked = PEEKED_BUFFERED;
    double asDouble = Double.parseDouble(peekedString); // don't catch this NumberFormatException.
    result = (int) asDouble;
    if (result != asDouble) { // Make sure no precision was lost casting to 'int'.
      throw new NumberFormatException("Expected an int but was " + peekedString + locationString());
    }
    peekedString = null;
    peeked = PEEKED_NONE;
    pathIndices[stackSize - 1]++; //#61
    return result;
  }

  /**
   * Closes this JSON reader and the underlying {@link java.io.Reader}.
   */
  /*#62 0 is less than length of stack*/
  /*stack has one element therefore stackSize = 1 is safe #63*/
  @SuppressWarnings({"array.access.unsafe.high.constant","assignment.type.incompatible"})
  public void close() throws IOException {
    peeked = PEEKED_NONE;
    stack[0] = JsonScope.CLOSED; //#62
    stackSize = 1; //#63
    in.close();
  }

  /**
   * Skips the next value recursively. If it is an object or array, all nested
   * elements are skipped. This method is intended for use when the JSON token
   * stream contains unrecognized or unhandled values.
   */
   /*#64 doPeek() returns the top of the stack, if it does not return one of the
   expected values, exception is thrown therefore stackSize > 1 and #68 is safe*/
   /* According to documentatiom buffer is taken for highest length token,
   pos + peekedNuberLength < buffer.length and is safe #67*/
   /*as stackSize > 1 #65 #66 is safe*/
  @SuppressWarnings({"array.access.unsafe.low","compound.assignment.type.incompatible","unary.decrement.type.incompatible"})
  public void skipValue() throws IOException {
    int count = 0;
    do {
      int p = peeked;
      if (p == PEEKED_NONE) {
        p = doPeek(); //#64
      }

      if (p == PEEKED_BEGIN_ARRAY) {
        push(JsonScope.EMPTY_ARRAY);
        count++;
      } else if (p == PEEKED_BEGIN_OBJECT) {
        push(JsonScope.EMPTY_OBJECT);
        count++;
      } else if (p == PEEKED_END_ARRAY) {
        stackSize--; //#65
        count--;
      } else if (p == PEEKED_END_OBJECT) {
        stackSize--; //#66
        count--;
      } else if (p == PEEKED_UNQUOTED_NAME || p == PEEKED_UNQUOTED) {
        skipUnquotedValue();
      } else if (p == PEEKED_SINGLE_QUOTED || p == PEEKED_SINGLE_QUOTED_NAME) {
        skipQuotedValue('\'');
      } else if (p == PEEKED_DOUBLE_QUOTED || p == PEEKED_DOUBLE_QUOTED_NAME) {
        skipQuotedValue('"');
      } else if (p == PEEKED_NUMBER) {
        pos += peekedNumberLength; //#67
      }
      peeked = PEEKED_NONE;
    } while (count != 0);

    pathIndices[stackSize - 1]++; //#68
    pathNames[stackSize - 1] = "null";
  }

  //#69 ensures stackSize is less than stack.length, therefore #70 is safe
  @SuppressWarnings({"array.access.unsafe.high","unary.increment.type.incompatible"})
  private void push(int newTop) {
    if (stackSize == stack.length) { //#69
      int newLength = stackSize * 2;
      stack = Arrays.copyOf(stack, newLength);
      pathIndices = Arrays.copyOf(pathIndices, newLength);
      pathNames = Arrays.copyOf(pathNames, newLength);
    }
    stack[stackSize++] = newTop; //#70
  }

  /**
   * Returns true once {@code limit - pos >= minimum}. If the data is
   * exhausted before that many characters are available, this returns
   * false.
   */
  /*#73 ensured that total is no more than buffer.length - limit,
  therefore #74 is safe*/
  /*#75 is safe as pos = 0 and incrementing pos once is less than buffer.length*/
  /*#73 has safe arguments as buffer.length > limit and buffer.length - limit > 0*/
  /*#72 arraycopy has valid arguments due to #71 as limit is less than buffer.length - pos*/
  @SuppressWarnings({"compound.assignment.type.incompatible","unary.increment.type.incompatible","argument.type.incompatible"})
  private boolean fillBuffer(int minimum) throws IOException {
    char[] buffer = this.buffer;
    lineStart -= pos;
    if (limit != pos) {
      limit -= pos; //#71
      System.arraycopy(buffer, pos, buffer, 0, limit); //#72
    } else {
      limit = 0;
    }

    pos = 0;
    int total;
    while ((total = in.read(buffer, limit, buffer.length - limit)) != -1) { //#73
      limit += total; //#74

      // if this is the first read, consume an optional byte order mark (BOM) if it exists
      if (lineNumber == 0 && lineStart == 0 && limit > 0 && buffer[0] == '\ufeff') {
        pos++; //#75
        lineStart++;
        minimum++;
      }

      if (limit >= minimum) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the next character in the stream that is neither whitespace nor a
   * part of a comment. When this returns, the returned character is always at
   * {@code buffer[pos-1]}; this means the caller can always push back the
   * returned character by decrementing {@code pos}.
   */
   /*As #77 c is a character being read from buffer pos > 0 therefore #78 is safe*/
   /*as #78 pos was decremented to push'/' to buffer, #79 is a valid and safe operation to consume back '/'*/
   /*#80 reads a character from the buffer therefore pos incremented #81 #84 is safe and valid*/
   /*if #82 returns true, terminating characters of a comment are read by incrementing pos by 2, therefore #83 is safe*/
  @SuppressWarnings({"unary.decrement.type.incompatible","unary.increment.type.incompatible","assignment.type.incompatible"})
  private @GTENegativeOne int nextNonWhitespace(boolean throwOnEof) throws IOException {
    /*
     * This code uses ugly local variables 'p' and 'l' representing the 'pos'
     * and 'limit' fields respectively. Using locals rather than fields saves
     * a few field reads for each whitespace character in a pretty-printed
     * document, resulting in a 5% speedup. We need to flush 'p' to its field
     * before any (potentially indirect) call to fillBuffer() and reread both
     * 'p' and 'l' after any (potentially indirect) call to the same method.
     */
    char[] buffer = this.buffer;
    @NonNegative @LTLengthOf("buffer") int p = pos;
    int l = limit;
    while (true) {
      if (p == l) { //#76
        pos = p;
        if (!fillBuffer(1)) {
          break;
        }
        p = pos;
        l = limit;
      }

      /*c stores the ascii value of a character which is NonNegative
      #76 ensures p < l therefore less than limit and buffer.length therefore buffer[p] is safe
      as p < l, p++ is within valid range*/
      @SuppressWarnings({"assignment.type.incompatible","array.access.unsafe.high","unary.increment.type.incompatible"})
      @NonNegative int c = buffer[p++];
      if (c == '\n') {
        lineNumber++;
        lineStart = p;
        continue;
      } else if (c == ' ' || c == '\r' || c == '\t') {
        continue;
      }

      if (c == '/') {//#77
        pos = p;
        if (p == l) {
          pos--; // push back '/' so it's still in the buffer when this method returns #78
          boolean charsLoaded = fillBuffer(2);
          pos++; // consume the '/' again #79
          if (!charsLoaded) {
            return c;
          }
        }

        checkLenient();
        char peek = buffer[pos]; //#80
        switch (peek) {
        case '*':
          // skip a /* c-style comment */
          pos++; //#81
          if (!skipTo("*/")) { //#82
            throw syntaxError("Unterminated comment");
          }
          p = pos + 2; //#83
          l = limit;
          continue;

        case '/':
          // skip a // end-of-line comment
          pos++; //#84
          skipToEndOfLine();
          p = pos;
          l = limit;
          continue;

        default:
          return c;
        }
      } else if (c == '#') {
        pos = p;
        /*
         * Skip a # hash end-of-line comment. The JSON RFC doesn't
         * specify this behaviour, but it's required to parse
         * existing documents. See http://b/2571423.
         */
        checkLenient();
        skipToEndOfLine();
        p = pos;
        l = limit;
      } else {
        pos = p;
        return c;
      }
    }
    if (throwOnEof) {
      throw new EOFException("End of input" + locationString());
    } else {
      return -1;
    }
  }

  private void checkLenient() throws IOException {
    if (!lenient) {
      throw syntaxError("Use JsonReader.setLenient(true) to accept malformed JSON");
    }
  }

  /**
   * Advances the position until after the next newline character. If the line
   * is terminated by "\r\n", the '\n' must be consumed as whitespace by the
   * caller.
   */
  //pos is ensured to be < limit #85, therefore #86 is safe
  @SuppressWarnings({"array.access.unsafe.high","unary.increment.type.incompatible"})
  private void skipToEndOfLine() throws IOException {
    while (pos < limit || fillBuffer(1)) { //#85
      char c = buffer[pos++]; //#86
      if (c == '\n') {
        lineNumber++;
        lineStart = pos;
        break;
      } else if (c == '\r') {
        break;
      }
    }
  }

  /**
   * @param toFind a string to search for. Must not contain a newline.
   */
  //pos+length is ensured to be less than limit #87 therefore #88 #88 are safe
  @SuppressWarnings({"array.access.unsafe.high","array.access.unsafe.low","unary.increment.type.incompatible"})
  private boolean skipTo(String toFind) throws IOException {
    int length = toFind.length();
    outer:
    for (; pos + length <= limit || fillBuffer(length); pos++) { //#87
      if (buffer[pos] == '\n') { //#88
        lineNumber++;
        lineStart = pos + 1;
        continue;
      }
      for (int c = 0; c < length; c++) {
        if (buffer[pos + c] != toFind.charAt(c)) { //#89
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  @Override public String toString() {
    return getClass().getSimpleName() + locationString();
  }

  String locationString() {
    int line = lineNumber + 1;
    int column = pos - lineStart + 1;
    return " at line " + line + " column " + column + " path " + getPath();
  }

  /**
   * Returns a <a href="http://goessner.net/articles/JsonPath/">JsonPath</a> to
   * the current location in the JSON value.
   */
  //Could not add annotation to i and not size #90
  @SuppressWarnings("assignment.type.incompatible")
  public String getPath() {
    StringBuilder result = new StringBuilder().append('$');
    for (@IndexFor("stack") int i = 0, size = stackSize; i < size; i++) { //#90
      switch (stack[i]) {
        case JsonScope.EMPTY_ARRAY:
        case JsonScope.NONEMPTY_ARRAY:
          result.append('[').append(pathIndices[i]).append(']');
          break;

        case JsonScope.EMPTY_OBJECT:
        case JsonScope.DANGLING_NAME:
        case JsonScope.NONEMPTY_OBJECT:
          result.append('.');
          if (pathNames[i] != null) {
            result.append(pathNames[i]);
          }
          break;

        case JsonScope.NONEMPTY_DOCUMENT:
        case JsonScope.EMPTY_DOCUMENT:
        case JsonScope.CLOSED:
          break;
      }
    }
    return result.toString();
  }

  /**
   * Unescapes the character identified by the character or characters that
   * immediately follow a backslash. The backslash '\' should have already
   * been read. This supports both unicode escapes "u000A" and two-character
   * escapes "\n".
   *
   * @throws NumberFormatException if any unicode escape sequences are
   *     malformed.
   */
  /*According to documentation, buffer is of highest possible length of token,
  and pos+4 was already ensured to be less than limit #92, therefore #94 is safe*/
  //the if #92 ensures 4 < buffer.length - pos therefore #93 is safe
  @SuppressWarnings({"compound.assignment.type.incompatible","argument.type.incompatible"})
  private char readEscapeCharacter() throws IOException {
    if (pos == limit && !fillBuffer(1)) { //#91
      throw syntaxError("Unterminated escape sequence");
    }

    //The above if #91 ensures pos < limit
    @SuppressWarnings({"unary.increment.type.incompatible"})
    char escaped = buffer[pos++];
    switch (escaped) {
    case 'u':
      if (pos + 4 > limit && !fillBuffer(4)) { //#92
        throw syntaxError("Unterminated escape sequence");
      }
      // Equivalent to Integer.parseInt(stringPool.get(buffer, pos, 4), 16);
      char result = 0;
      for (int i = pos, end = i + 4; i < end; i++) {
        //if #92 ensures pos + 4 < limit, therefore index i is safe
        @SuppressWarnings("array.access.unsafe.high")
        char c = buffer[i];
        result <<= 4;
        if (c >= '0' && c <= '9') {
          result += (c - '0');
        } else if (c >= 'a' && c <= 'f') {
          result += (c - 'a' + 10);
        } else if (c >= 'A' && c <= 'F') {
          result += (c - 'A' + 10);
        } else {
          throw new NumberFormatException("\\u" + new String(buffer, pos, 4)); //#93
        }
      }
      pos += 4; //#94
      return result;

    case 't':
      return '\t';

    case 'b':
      return '\b';

    case 'n':
      return '\n';

    case 'r':
      return '\r';

    case 'f':
      return '\f';

    case '\n':
      lineNumber++;
      lineStart = pos;
      // fall-through

    case '\'':
    case '"':
    case '\\':
    case '/':
    	return escaped;
    default:
    	// throw error when none of the above cases are matched
    	throw syntaxError("Invalid escape sequence");
    }
  }

  /**
   * Throws a new IO exception with the given message and a context snippet
   * with this reader's content.
   */
  private IOException syntaxError(String message) throws IOException {
    throw new MalformedJsonException(message + locationString());
  }

  /**
   * Consumes the non-execute prefix if it exists.
   */
  //ALready been checked if p+5 < limit #96 therefore no unsafe access in #97
  //acc to doc returned character by nextNonWhitespace is always at pos-1 therefore decrementing is safe #95, as pos>0
  /*According to documentation buffer is of highest possible length of token,
  and pos+5 was already ensured to be less than limit #96, therefore #98 is safe*/
  @SuppressWarnings({"array.access.unsafe.high","array.access.unsafe.low","compound.assignment.type.incompatible","unary.decrement.type.incompatible"})
  private void consumeNonExecutePrefix() throws IOException {
    // fast forward through the leading whitespace
    nextNonWhitespace(true);
    pos--; //#95

    int p = pos;
    if (p + 5 > limit && !fillBuffer(5)) { //#96
      return;
    }

    char[] buf = buffer;
    if(buf[p] != ')' || buf[p + 1] != ']' || buf[p + 2] != '}' || buf[p + 3] != '\'' || buf[p + 4] != '\n') { //#97
      return; // not a security token!
    }

    // we consumed a security token!
    pos += 5; //#98
  }

  static {
    JsonReaderInternalAccess.INSTANCE = new JsonReaderInternalAccess() {
      @Override public void promoteNameToValue(JsonReader reader) throws IOException {
        if (reader instanceof JsonTreeReader) {
          ((JsonTreeReader)reader).promoteNameToValue();
          return;
        }
        int p = reader.peeked;
        if (p == PEEKED_NONE) {
          p = reader.doPeek();
        }
        if (p == PEEKED_DOUBLE_QUOTED_NAME) {
          reader.peeked = PEEKED_DOUBLE_QUOTED;
        } else if (p == PEEKED_SINGLE_QUOTED_NAME) {
          reader.peeked = PEEKED_SINGLE_QUOTED;
        } else if (p == PEEKED_UNQUOTED_NAME) {
          reader.peeked = PEEKED_UNQUOTED;
        } else {
          throw new IllegalStateException(
              "Expected a name but was " + reader.peek() + reader.locationString());
        }
      }
    };
  }
}
