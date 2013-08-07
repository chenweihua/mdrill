package org.apache.lucene.search;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermDocs;
import org.apache.lucene.index.TermEnum;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.StringHelper;
import org.apache.lucene.util.FieldCacheSanityChecker;

/**
 * Expert: The default cache implementation, storing all values in memory.
 * A WeakHashMap is used for storage.
 *
 * <p>Created: May 19, 2004 4:40:36 PM
 *
 * @since   lucene 1.4
 */
class FieldCacheImpl implements FieldCache {
	
  private Map<Class<?>,Cache> caches;
  FieldCacheImpl() {
    init();
  }
  private synchronized void init() {
    caches = new HashMap<Class<?>,Cache>(9);
    caches.put(Byte.TYPE, new ByteCache(this));
    caches.put(Short.TYPE, new ShortCache(this));
    caches.put(Integer.TYPE, new IntCache(this));
    caches.put(Float.TYPE, new FloatCache(this));
    caches.put(Long.TYPE, new LongCache(this));
    caches.put(Double.TYPE, new DoubleCache(this));
    caches.put(String.class, new StringCache(this));
    caches.put(StringIndex.class, new StringIndexCache(this));
    caches.put(DocsWithFieldCache.class, new DocsWithFieldCache(this));
  }

  public synchronized void purgeAllCaches() {
    init();
  }

  public synchronized void purge(IndexReader r) {
    for(Cache c : caches.values()) {
      c.purge(r);
    }
  }
  
  public synchronized CacheEntry[] getCacheEntries() {
    List<CacheEntry> result = new ArrayList<CacheEntry>(17);
    for(final Map.Entry<Class<?>,Cache> cacheEntry: caches.entrySet()) {
      final Cache cache = cacheEntry.getValue();
      final Class<?> cacheType = cacheEntry.getKey();
      synchronized(cache.readerCache) {
        for (final Map.Entry<Object,Map<Entry, Object>> readerCacheEntry : cache.readerCache.entrySet()) {
          final Object readerKey = readerCacheEntry.getKey();
          if (readerKey == null) continue;
          final Map<Entry, Object> innerCache = readerCacheEntry.getValue();
          for (final Map.Entry<Entry, Object> mapEntry : innerCache.entrySet()) {
            Entry entry = mapEntry.getKey();
            result.add(new CacheEntryImpl(readerKey, entry.field,
                                          cacheType, entry.custom,
                                          mapEntry.getValue()));
          }
        }
      }
    }
    return result.toArray(new CacheEntry[result.size()]);
  }
  
  private static final class CacheEntryImpl extends CacheEntry {
    private final Object readerKey;
    private final String fieldName;
    private final Class<?> cacheType;
    private final Object custom;
    private final Object value;
    CacheEntryImpl(Object readerKey, String fieldName,
                   Class<?> cacheType,
                   Object custom,
                   Object value) {
        this.readerKey = readerKey;
        this.fieldName = fieldName;
        this.cacheType = cacheType;
        this.custom = custom;
        this.value = value;

        // :HACK: for testing.
//         if (null != locale || SortField.CUSTOM != sortFieldType) {
//           throw new RuntimeException("Locale/sortFieldType: " + this);
//         }

    }
    @Override
    public Object getReaderKey() { return readerKey; }
    @Override
    public String getFieldName() { return fieldName; }
    @Override
    public Class<?> getCacheType() { return cacheType; }
    @Override
    public Object getCustom() { return custom; }
    @Override
    public Object getValue() { return value; }
  }

  /**
   * Hack: When thrown from a Parser (NUMERIC_UTILS_* ones), this stops
   * processing terms and returns the current FieldCache
   * array.
   */
  static final class StopFillCacheException extends RuntimeException {
  }

  final static IndexReader.ReaderFinishedListener purgeReader = new IndexReader.ReaderFinishedListener() {
    // @Override -- not until Java 1.6
    public void finished(IndexReader reader) {
      FieldCache.DEFAULT.purge(reader);
    }
  };

  /** Expert: Internal cache. */
  abstract static class Cache {
    Cache() {
      this.wrapper = null;
    }

    Cache(FieldCacheImpl wrapper) {
      this.wrapper = wrapper;
    }

    final FieldCacheImpl wrapper;

    final Map<Object,Map<Entry,Object>> readerCache = new WeakHashMap<Object,Map<Entry,Object>>();
    
    protected abstract Object createValue(IndexReader reader, Entry key, boolean setDocsWithField)
        throws IOException;

    /** Remove this reader from the cache, if present. */
    public void purge(IndexReader r) {
      Object readerKey = r.getCoreCacheKey();
      synchronized(readerCache) {
        readerCache.remove(readerKey);
      }
    }

    /** Sets the key to the value for the provided reader;
     *  if the key is already set then this doesn't change it. */
    public void put(IndexReader reader, Entry key, Object value) {
      final Object readerKey = reader.getCoreCacheKey();
      synchronized (readerCache) {
        Map<Entry,Object> innerCache = readerCache.get(readerKey);
        if (innerCache == null) {
          // First time this reader is using FieldCache
          innerCache = new HashMap<Entry,Object>();
          readerCache.put(readerKey, innerCache);
          reader.addReaderFinishedListener(purgeReader);
        }
        if (innerCache.get(key) == null) {
          innerCache.put(key, value);
        } else {
          // Another thread beat us to it; leave the current
          // value
        }
      }
    }

    public Object get(IndexReader reader, Entry key, boolean setDocsWithField) throws IOException {
      Map<Entry,Object> innerCache;
      Object value;
      final Object readerKey = reader.getCoreCacheKey();
      synchronized (readerCache) {
        innerCache = readerCache.get(readerKey);
        if (innerCache == null) {
          // First time this reader is using FieldCache
          innerCache = new HashMap<Entry,Object>();
          readerCache.put(readerKey, innerCache);
          reader.addReaderFinishedListener(purgeReader);
          value = null;
        } else {
          value = innerCache.get(key);
        }
        if (value == null) {
          value = new CreationPlaceholder();
          innerCache.put(key, value);
        }
      }
      if (value instanceof CreationPlaceholder) {
        synchronized (value) {
          CreationPlaceholder progress = (CreationPlaceholder) value;
          if (progress.value == null) {
            progress.value = createValue(reader, key, setDocsWithField);
            synchronized (readerCache) {
              innerCache.put(key, progress.value);
            }

            // Only check if key.custom (the parser) is
            // non-null; else, we check twice for a single
            // call to FieldCache.getXXX
            if (key.custom != null && wrapper != null) {
              final PrintStream infoStream = wrapper.getInfoStream();
              if (infoStream != null) {
                printNewInsanity(infoStream, progress.value);
              }
            }
          }
          return progress.value;
        }
      }
      return value;
    }

    private void printNewInsanity(PrintStream infoStream, Object value) {
      final FieldCacheSanityChecker.Insanity[] insanities = FieldCacheSanityChecker.checkSanity(wrapper);
      for(int i=0;i<insanities.length;i++) {
        final FieldCacheSanityChecker.Insanity insanity = insanities[i];
        final CacheEntry[] entries = insanity.getCacheEntries();
        for(int j=0;j<entries.length;j++) {
          if (entries[j].getValue() == value) {
            // OK this insanity involves our entry
            infoStream.println("WARNING: new FieldCache insanity created\nDetails: " + insanity.toString());
            infoStream.println("\nStack:\n");
            new Throwable().printStackTrace(infoStream);
            break;
          }
        }
      }
    }
  }

  /** Expert: Every composite-key in the internal cache is of this type. */
  static class Entry {
    final String field;        // which Fieldable
    final Object custom;       // which custom comparator or parser

    /** Creates one of these objects for a custom comparator/parser. */
    Entry (String field, Object custom) {
      this.field = StringHelper.intern(field);
      this.custom = custom;
    }

    /** Two of these are equal iff they reference the same field and type. */
    @Override
    public boolean equals (Object o) {
      if (o instanceof Entry) {
        Entry other = (Entry) o;
        if (other.field == field) {
          if (other.custom == null) {
            if (custom == null) return true;
          } else if (other.custom.equals (custom)) {
            return true;
          }
        }
      }
      return false;
    }

    /** Composes a hashcode based on the field and type. */
    @Override
    public int hashCode() {
      return field.hashCode() ^ (custom==null ? 0 : custom.hashCode());
    }
  }

  // inherit javadocs
  public byte[] getBytes (IndexReader reader, String field) throws IOException {
    return getBytes(reader, field, null, false);
  }

  // inherit javadocs
  public byte[] getBytes(IndexReader reader, String field, ByteParser parser)
      throws IOException {
    return getBytes(reader, field, parser, false);
  }

  public byte[] getBytes(IndexReader reader, String field, ByteParser parser, boolean setDocsWithField)
      throws IOException {
    return (byte[]) caches.get(Byte.TYPE).get(reader, new Entry(field, parser), setDocsWithField);
  }

  static final class ByteCache extends Cache {
    ByteCache(FieldCacheImpl wrapper) {
      super(wrapper);
    }

    @Override
    protected Object createValue(IndexReader reader, Entry entryKey, boolean setDocsWithField)
        throws IOException {
      Entry entry = entryKey;
      String field = entry.field;
      ByteParser parser = (ByteParser) entry.custom;
      if (parser == null) {
        return wrapper.getBytes(reader, field, FieldCache.DEFAULT_BYTE_PARSER, setDocsWithField);
      }
      final int maxDoc = reader.maxDoc();
      final byte[] retArray = new byte[maxDoc];
      TermDocs termDocs = reader.termDocs();
      TermEnum termEnum = reader.terms (new Term (field));
      FixedBitSet docsWithField = null;
      try {
        do {
          Term term = termEnum.term();
          if (term==null || term.field() != field) break;
          byte termval = parser.parseByte(term.text());
          termDocs.seek (termEnum);
          while (termDocs.next()) {
            final int docID = termDocs.doc();
            retArray[docID] = termval;
            if (setDocsWithField) {
              if (docsWithField == null) {
                // Lazy init
                docsWithField = new FixedBitSet(maxDoc);
              }
              docsWithField.set(docID);
            }
          }
        } while (termEnum.next());
      } catch (StopFillCacheException stop) {
      } finally {
        termDocs.close();
        termEnum.close();
      }
      if (setDocsWithField) {
        wrapper.setDocsWithField(reader, field, docsWithField);
      }
      return retArray;
    }
  }
  
  // inherit javadocs
  public short[] getShorts (IndexReader reader, String field) throws IOException {
    return getShorts(reader, field, null, false);
  }

  // inherit javadocs
  public short[] getShorts(IndexReader reader, String field, ShortParser parser)
      throws IOException {
    return getShorts(reader, field, parser, false);
  }

  // inherit javadocs
  public short[] getShorts(IndexReader reader, String field, ShortParser parser, boolean setDocsWithField)
      throws IOException {
    return (short[]) caches.get(Short.TYPE).get(reader, new Entry(field, parser), setDocsWithField);
  }

  static final class ShortCache extends Cache {
    ShortCache(FieldCacheImpl wrapper) {
      super(wrapper);
    }

    @Override
    protected Object createValue(IndexReader reader, Entry entryKey, boolean setDocsWithField)
        throws IOException {
      Entry entry =  entryKey;
      String field = entry.field;
      ShortParser parser = (ShortParser) entry.custom;
      if (parser == null) {
        return wrapper.getShorts(reader, field, FieldCache.DEFAULT_SHORT_PARSER, setDocsWithField);
      }
      final int maxDoc = reader.maxDoc();
      final short[] retArray = new short[maxDoc];
      TermDocs termDocs = reader.termDocs();
      TermEnum termEnum = reader.terms (new Term (field));
      FixedBitSet docsWithField = null;
      try {
        do {
          Term term = termEnum.term();
          if (term==null || term.field() != field) break;
          short termval = parser.parseShort(term.text());
          termDocs.seek (termEnum);
          while (termDocs.next()) {
            final int docID = termDocs.doc();
            retArray[docID] = termval;
            if (setDocsWithField) {
              if (docsWithField == null) {
                // Lazy init
                docsWithField = new FixedBitSet(maxDoc);
              }
              docsWithField.set(docID);
            }
          }
        } while (termEnum.next());
      } catch (StopFillCacheException stop) {
      } finally {
        termDocs.close();
        termEnum.close();
      }
      if (setDocsWithField) {
        wrapper.setDocsWithField(reader, field, docsWithField);
      }
      return retArray;
    }
  }
  
  // null Bits means no docs matched
  void setDocsWithField(IndexReader reader, String field, Bits docsWithField) {
    final int maxDoc = reader.maxDoc();
    final Bits bits;
    if (docsWithField == null) {
      bits = new Bits.MatchNoBits(maxDoc);
    } else if (docsWithField instanceof FixedBitSet) {
      final int numSet = ((FixedBitSet) docsWithField).cardinality();
      if (numSet >= maxDoc) {
        // The cardinality of the BitSet is maxDoc if all documents have a value.
        assert numSet == maxDoc;
        bits = new Bits.MatchAllBits(maxDoc);
      } else {
        bits = docsWithField;
      }
    } else {
      bits = docsWithField;
    }
    caches.get(DocsWithFieldCache.class).put(reader, new Entry(field, null), bits);
  }

  // inherit javadocs
  public int[] getInts (IndexReader reader, String field) throws IOException {
    return getInts(reader, field, null);
  }

  // inherit javadocs
  public int[] getInts(IndexReader reader, String field, IntParser parser)
      throws IOException {
    return getInts(reader, field, parser, false);
  }

  // inherit javadocs
  public int[] getInts(IndexReader reader, String field, IntParser parser, boolean setDocsWithField)
      throws IOException {
    return (int[]) caches.get(Integer.TYPE).get(reader, new Entry(field, parser), setDocsWithField);
  }

  static final class IntCache extends Cache {
    IntCache(FieldCacheImpl wrapper) {
      super(wrapper);
    }

    @Override
    protected Object createValue(IndexReader reader, Entry entryKey, boolean setDocsWithField)
        throws IOException {
      Entry entry = entryKey;
      String field = entry.field;
      IntParser parser = (IntParser) entry.custom;
      if (parser == null) {
        try {
          return wrapper.getInts(reader, field, DEFAULT_INT_PARSER, setDocsWithField);
        } catch (NumberFormatException ne) {
          return wrapper.getInts(reader, field, NUMERIC_UTILS_INT_PARSER, setDocsWithField);
        }
      }
      final int maxDoc = reader.maxDoc();
      int[] retArray = null;
      TermDocs termDocs = reader.termDocs();
      TermEnum termEnum = reader.terms (new Term (field));
      FixedBitSet docsWithField = null;
      try {
        do {
          Term term = termEnum.term();
          if (term==null || term.field() != field) break;
          int termval = parser.parseInt(term.text());
          if (retArray == null) { // late init
            retArray = new int[maxDoc];
          }
          termDocs.seek (termEnum);
          while (termDocs.next()) {
            final int docID = termDocs.doc();
            retArray[docID] = termval;
            if (setDocsWithField) {
              if (docsWithField == null) {
                // Lazy init
                docsWithField = new FixedBitSet(maxDoc);
              }
              docsWithField.set(docID);
            }
          }
        } while (termEnum.next());
      } catch (StopFillCacheException stop) {
      } finally {
        termDocs.close();
        termEnum.close();
      }
      if (setDocsWithField) {
        wrapper.setDocsWithField(reader, field, docsWithField);
      }
      if (retArray == null) { // no values
        retArray = new int[maxDoc];
      }
      return retArray;
    }
  }
  
  public Bits getDocsWithField(IndexReader reader, String field)
      throws IOException {
    return (Bits) caches.get(DocsWithFieldCache.class).get(reader, new Entry(field, null), false);
  }

  static final class DocsWithFieldCache extends Cache {
    DocsWithFieldCache(FieldCacheImpl wrapper) {
      super(wrapper);
    }
    
    @Override
    protected Object createValue(IndexReader reader, Entry entryKey, boolean setDocsWithField /* ignored */)
    throws IOException {
      final Entry entry = entryKey;
      final String field = entry.field;      
      FixedBitSet res = null;
      final TermDocs termDocs = reader.termDocs();
      final TermEnum termEnum = reader.terms(new Term(field));
      try {
        do {
          final Term term = termEnum.term();
          if (term == null || term.field() != field) break;
          if (res == null) // late init
            res = new FixedBitSet(reader.maxDoc());
          termDocs.seek(termEnum);
          while (termDocs.next()) {
            res.set(termDocs.doc());
          }
        } while (termEnum.next());
      } finally {
        termDocs.close();
        termEnum.close();
      }
      if (res == null)
        return new Bits.MatchNoBits(reader.maxDoc());
      final int numSet = res.cardinality();
      if (numSet >= reader.numDocs()) {
        // The cardinality of the BitSet is numDocs if all documents have a value.
        // As deleted docs are not in TermDocs, this is always true
        assert numSet == reader.numDocs();
        return new Bits.MatchAllBits(reader.maxDoc());
      }
      return res;
    }
  }

  // inherit javadocs
  public float[] getFloats (IndexReader reader, String field)
    throws IOException {
    return getFloats(reader, field, null, false);
  }

  // inherit javadocs
  public float[] getFloats(IndexReader reader, String field, FloatParser parser)
    throws IOException {
    return getFloats(reader, field, parser, false);
  }

  public float[] getFloats(IndexReader reader, String field, FloatParser parser, boolean setDocsWithField)
    throws IOException {

    return (float[]) caches.get(Float.TYPE).get(reader, new Entry(field, parser), setDocsWithField);
  }

  static final class FloatCache extends Cache {
    FloatCache(FieldCacheImpl wrapper) {
      super(wrapper);
    }

    @Override
    protected Object createValue(IndexReader reader, Entry entryKey, boolean setDocsWithField)
        throws IOException {
      Entry entry = entryKey;
      String field = entry.field;
      FloatParser parser = (FloatParser) entry.custom;
      if (parser == null) {
        try {
          return wrapper.getFloats(reader, field, DEFAULT_FLOAT_PARSER, setDocsWithField);
        } catch (NumberFormatException ne) {
          return wrapper.getFloats(reader, field, NUMERIC_UTILS_FLOAT_PARSER, setDocsWithField);
        }
      }
      final int maxDoc = reader.maxDoc();
      float[] retArray = null;
      TermDocs termDocs = reader.termDocs();
      TermEnum termEnum = reader.terms (new Term (field));
      FixedBitSet docsWithField = null;
      try {
        do {
          Term term = termEnum.term();
          if (term==null || term.field() != field) break;
          float termval = parser.parseFloat(term.text());
          if (retArray == null) { // late init
            retArray = new float[maxDoc];
          }
          termDocs.seek (termEnum);
          while (termDocs.next()) {
            final int docID = termDocs.doc();
            retArray[docID] = termval;
            if (setDocsWithField) {
              if (docsWithField == null) {
                // Lazy init
                docsWithField = new FixedBitSet(maxDoc);
              }
              docsWithField.set(docID);
            }
          }
        } while (termEnum.next());
      } catch (StopFillCacheException stop) {
      } finally {
        termDocs.close();
        termEnum.close();
      }
      if (setDocsWithField) {
        wrapper.setDocsWithField(reader, field, docsWithField);
      }
      if (retArray == null) { // no values
        retArray = new float[maxDoc];
      }
      return retArray;
    }
  }

  public long[] getLongs(IndexReader reader, String field) throws IOException {
    return getLongs(reader, field, null, false);
  }
  
  // inherit javadocs
  public long[] getLongs(IndexReader reader, String field, FieldCache.LongParser parser)
      throws IOException {
    return getLongs(reader, field, parser, false);
  }

  // inherit javadocs
  public long[] getLongs(IndexReader reader, String field, FieldCache.LongParser parser, boolean setDocsWithField)
      throws IOException {
    return (long[]) caches.get(Long.TYPE).get(reader, new Entry(field, parser), setDocsWithField);
  }

  static final class LongCache extends Cache {
    LongCache(FieldCacheImpl wrapper) {
      super(wrapper);
    }

    @Override
    protected Object createValue(IndexReader reader, Entry entry, boolean setDocsWithField)
        throws IOException {
      String field = entry.field;
      FieldCache.LongParser parser = (FieldCache.LongParser) entry.custom;
      if (parser == null) {
        try {
          return wrapper.getLongs(reader, field, DEFAULT_LONG_PARSER, setDocsWithField);
        } catch (NumberFormatException ne) {
          return wrapper.getLongs(reader, field, NUMERIC_UTILS_LONG_PARSER, setDocsWithField);
        }
      }
      final int maxDoc = reader.maxDoc();
      long[] retArray = null;
      TermDocs termDocs = reader.termDocs();
      TermEnum termEnum = reader.terms (new Term(field));
      FixedBitSet docsWithField = null;
      try {
        do {
          Term term = termEnum.term();
          if (term==null || term.field() != field) break;
          long termval = parser.parseLong(term.text());
          if (retArray == null) { // late init
            retArray = new long[maxDoc];
          }
          termDocs.seek (termEnum);
          while (termDocs.next()) {
            final int docID = termDocs.doc();
            retArray[docID] = termval;
            if (setDocsWithField) {
              if (docsWithField == null) {
                // Lazy init
                docsWithField = new FixedBitSet(maxDoc);
              }
              docsWithField.set(docID);
            }
          }
        } while (termEnum.next());
      } catch (StopFillCacheException stop) {
      } finally {
        termDocs.close();
        termEnum.close();
      }
      if (setDocsWithField) {
        wrapper.setDocsWithField(reader, field, docsWithField);
      }
      if (retArray == null) { // no values
        retArray = new long[maxDoc];
      }
      return retArray;
    }
  }

  // inherit javadocs
  public double[] getDoubles(IndexReader reader, String field)
    throws IOException {
    return getDoubles(reader, field, null, false);
  }

  // inherit javadocs
  public double[] getDoubles(IndexReader reader, String field, FieldCache.DoubleParser parser)
      throws IOException {
    return getDoubles(reader, field, parser, false);
  }

  // inherit javadocs
  public double[] getDoubles(IndexReader reader, String field, FieldCache.DoubleParser parser, boolean setDocsWithField)
      throws IOException {
    return (double[]) caches.get(Double.TYPE).get(reader, new Entry(field, parser), setDocsWithField);
  }

  static final class DoubleCache extends Cache {
    DoubleCache(FieldCacheImpl wrapper) {
      super(wrapper);
    }

    @Override
    protected Object createValue(IndexReader reader, Entry entryKey, boolean setDocsWithField)
        throws IOException {
      Entry entry = entryKey;
      String field = entry.field;
      FieldCache.DoubleParser parser = (FieldCache.DoubleParser) entry.custom;
      if (parser == null) {
        try {
          return wrapper.getDoubles(reader, field, DEFAULT_DOUBLE_PARSER, setDocsWithField);
        } catch (NumberFormatException ne) {
          return wrapper.getDoubles(reader, field, NUMERIC_UTILS_DOUBLE_PARSER, setDocsWithField);
        }
      }
      final int maxDoc = reader.maxDoc();
      double[] retArray = null;
      TermDocs termDocs = reader.termDocs();
      TermEnum termEnum = reader.terms (new Term (field));
      FixedBitSet docsWithField = null;
      try {
        do {
          Term term = termEnum.term();
          if (term==null || term.field() != field) break;
          double termval = parser.parseDouble(term.text());
          if (retArray == null) { // late init
            retArray = new double[maxDoc];
          }
          termDocs.seek (termEnum);
          while (termDocs.next()) {
            final int docID = termDocs.doc();
            retArray[docID] = termval;
            if (setDocsWithField) {
              if (docsWithField == null) {
                // Lazy init
                docsWithField = new FixedBitSet(maxDoc);
              }
              docsWithField.set(docID);
            }
          }
        } while (termEnum.next());
      } catch (StopFillCacheException stop) {
      } finally {
        termDocs.close();
        termEnum.close();
      }
      if (setDocsWithField) {
        wrapper.setDocsWithField(reader, field, docsWithField);
      }
      if (retArray == null) { // no values
        retArray = new double[maxDoc];
      }
      return retArray;
    }
  }

  // inherit javadocs
  public String[] getStrings(IndexReader reader, String field)
      throws IOException {
    return (String[]) caches.get(String.class).get(reader, new Entry(field, (Parser)null), false);
  }

  static final class StringCache extends Cache {
    StringCache(FieldCacheImpl wrapper) {
      super(wrapper);
    }

    @Override
    protected Object createValue(IndexReader reader, Entry entryKey, boolean setDocsWithField /* ignored */)
        throws IOException {
      String field = StringHelper.intern(entryKey.field);
      final String[] retArray = new String[reader.maxDoc()];
      TermDocs termDocs = reader.termDocs();
      TermEnum termEnum = reader.terms (new Term (field));
      final int termCountHardLimit = reader.maxDoc();
      int termCount = 0;
      try {
        do {
          if (termCount++ == termCountHardLimit) {
            // app is misusing the API (there is more than
            // one term per doc); in this case we make best
            // effort to load what we can (see LUCENE-2142)
            break;
          }

          Term term = termEnum.term();
          if (term==null || term.field() != field) break;
          String termval = term.text();
          termDocs.seek (termEnum);
          while (termDocs.next()) {
            retArray[termDocs.doc()] = termval;
          }
        } while (termEnum.next());
      } finally {
        termDocs.close();
        termEnum.close();
      }
      return retArray;
    }
  }

  // inherit javadocs
  public StringIndex getStringIndex(IndexReader reader, String field)
      throws IOException {
    return (StringIndex) caches.get(StringIndex.class).get(reader, new Entry(field, (Parser)null), false);
  }

  static final class StringIndexCache extends Cache {
    StringIndexCache(FieldCacheImpl wrapper) {
      super(wrapper);
    }

    @Override
    protected Object createValue(IndexReader reader, Entry entryKey, boolean setDocsWithField /* ignored */)
        throws IOException {
      String field = StringHelper.intern(entryKey.field);
      final int[] retArray = new int[reader.maxDoc()];
      String[] mterms = new String[reader.maxDoc()+1];
      TermDocs termDocs = reader.termDocs();
      TermEnum termEnum = reader.terms (new Term (field));
      int t = 0;  // current term number

      // an entry for documents that have no terms in this field
      // should a document with no terms be at top or bottom?
      // this puts them at the top - if it is changed, FieldDocSortedHitQueue
      // needs to change as well.
      mterms[t++] = null;

      try {
        do {
          Term term = termEnum.term();
          if (term==null || term.field() != field || t >= mterms.length) break;

          // store term text
          mterms[t] = term.text();

          termDocs.seek (termEnum);
          while (termDocs.next()) {
            retArray[termDocs.doc()] = t;
          }

          t++;
        } while (termEnum.next());
      } finally {
        termDocs.close();
        termEnum.close();
      }

      if (t == 0) {
        // if there are no terms, make the term array
        // have a single null entry
        mterms = new String[1];
      } else if (t < mterms.length) {
        // if there are less terms than documents,
        // trim off the dead array space
        String[] terms = new String[t];
        System.arraycopy (mterms, 0, terms, 0, t);
        mterms = terms;
      }

      StringIndex value = new StringIndex (retArray, mterms);
      return value;
    }
  }

  private volatile PrintStream infoStream;

  public void setInfoStream(PrintStream stream) {
    infoStream = stream;
  }

  public PrintStream getInfoStream() {
    return infoStream;
  }
}

