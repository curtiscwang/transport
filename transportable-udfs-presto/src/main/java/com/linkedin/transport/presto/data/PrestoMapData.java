/**
 * Copyright 2018 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.transport.presto.data;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.linkedin.transport.api.StdFactory;
import com.linkedin.transport.api.data.PlatformData;
import com.linkedin.transport.api.data.MapData;
import com.linkedin.transport.presto.PrestoFactory;
import com.linkedin.transport.presto.PrestoWrapper;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.block.PageBuilderStatus;
import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.type.BooleanType;
import io.prestosql.spi.type.MapType;
import io.prestosql.spi.type.Type;
import java.lang.invoke.MethodHandle;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

import static io.prestosql.metadata.Signature.*;
import static io.prestosql.spi.StandardErrorCode.*;
import static io.prestosql.spi.type.TypeUtils.*;


public class PrestoMapData<K, V> extends PrestoData implements MapData<K, V> {

  final Type _keyType;
  final Type _valueType;
  final Type _mapType;
  final MethodHandle _keyEqualsMethod;
  final StdFactory _stdFactory;
  Block _block;

  public PrestoMapData(Type mapType, StdFactory stdFactory) {
    BlockBuilder mutable = mapType.createBlockBuilder(new PageBuilderStatus().createBlockBuilderStatus(), 1);
    mutable.beginBlockEntry();
    mutable.closeEntry();
    _block = ((MapType) mapType).getObject(mutable.build(), 0);

    _keyType = ((MapType) mapType).getKeyType();
    _valueType = ((MapType) mapType).getValueType();
    _mapType = mapType;

    _stdFactory = stdFactory;
    _keyEqualsMethod = ((PrestoFactory) stdFactory).getScalarFunctionImplementation(
            internalOperator(OperatorType.EQUAL, BooleanType.BOOLEAN, ImmutableList.of(_keyType, _keyType)))
        .getMethodHandle();
  }

  public PrestoMapData(Block block, Type mapType, StdFactory stdFactory) {
    this(mapType, stdFactory);
    _block = block;
  }

  @Override
  public int size() {
    return _block.getPositionCount() / 2;
  }

  @Override
  public V get(K key) {
    Object prestoKey = PrestoWrapper.getPlatformData(key);
    int i = seekKey(prestoKey);
    if (i != -1) {
      Object value = readNativeValue(_valueType, _block, i);
      return (V) PrestoWrapper.createStdData(value, _valueType, _stdFactory);
    } else {
      return null;
    }
  }

  // TODO: Do not copy the _mutable BlockBuilder on every update. As long as updates are append-only or for fixed-size
  // types, we can skip copying.
  @Override
  public void put(K key, V value) {
    BlockBuilder mutable = _mapType.createBlockBuilder(new PageBuilderStatus().createBlockBuilderStatus(), 1);
    BlockBuilder entryBuilder = mutable.beginBlockEntry();
    Object prestoKey = PrestoWrapper.getPlatformData(key);
    int valuePosition = seekKey(prestoKey);
    for (int i = 0; i < _block.getPositionCount(); i += 2) {
      // Write the current key to the map
      _keyType.appendTo(_block, i, entryBuilder);
      // Find out if we need to change the corresponding value
      if (i == valuePosition - 1) {
        // Use the user-supplied value
        PrestoWrapper.writeToBlock(value, entryBuilder);
      } else {
        // Use the existing value in original _block
        _valueType.appendTo(_block, i + 1, entryBuilder);
      }
    }
    if (valuePosition == -1) {
      PrestoWrapper.writeToBlock(key, entryBuilder);
      PrestoWrapper.writeToBlock(value, entryBuilder);
    }

    mutable.closeEntry();
    _block = ((MapType) _mapType).getObject(mutable.build(), 0);
  }

  public Set<K> keySet() {
    return new AbstractSet<K>() {
      @Override
      public Iterator<K> iterator() {
        return new Iterator<K>() {
          int i = -2;

          @Override
          public boolean hasNext() {
            return !(i + 2 == size() * 2);
          }

          @Override
          public K next() {
            i += 2;
            return (K) PrestoWrapper.createStdData(readNativeValue(_keyType, _block, i), _keyType, _stdFactory);
          }
        };
      }

      @Override
      public int size() {
        return PrestoMapData.this.size();
      }
    };
  }

  @Override
  public Collection<V> values() {
    return new AbstractCollection<V>() {

      @Override
      public Iterator<V> iterator() {
        return new Iterator<V>() {
          int i = -2;

          @Override
          public boolean hasNext() {
            return !(i + 2 == size() * 2);
          }

          @Override
          public V next() {
            i += 2;
            return
                (V) PrestoWrapper.createStdData(
                    readNativeValue(_valueType, _block, i + 1), _valueType, _stdFactory
                );
          }
        };
      }

      @Override
      public int size() {
        return PrestoMapData.this.size();
      }
    };
  }

  @Override
  public boolean containsKey(K key) {
    return get(key) != null;
  }

  @Override
  public Object getUnderlyingData() {
    return _block;
  }

  @Override
  public void setUnderlyingData(Object value) {
    _block = (Block) value;
  }

  private int seekKey(Object key) {
    for (int i = 0; i < _block.getPositionCount(); i += 2) {
      try {
        if ((boolean) _keyEqualsMethod.invoke(readNativeValue(_keyType, _block, i), key)) {
          return i + 1;
        }
      } catch (Throwable t) {
        Throwables.propagateIfInstanceOf(t, Error.class);
        Throwables.propagateIfInstanceOf(t, PrestoException.class);
        throw new PrestoException(GENERIC_INTERNAL_ERROR, t);
      }
    }
    return -1;
  }

  @Override
  public void writeToBlock(BlockBuilder blockBuilder) {
    _mapType.writeObject(blockBuilder, _block);
  }
}