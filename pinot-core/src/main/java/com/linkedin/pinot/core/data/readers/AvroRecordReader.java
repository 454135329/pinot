/**
 * Copyright (C) 2014-2015 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.core.data.readers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import java.util.zip.GZIPInputStream;
import org.apache.avro.Schema.Field;
import org.apache.avro.Schema.Type;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.generic.GenericData.Array;
import org.apache.avro.generic.GenericData.Record;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.util.Utf8;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.Predicate;

import com.linkedin.pinot.common.data.FieldSpec;
import com.linkedin.pinot.common.data.FieldSpec.DataType;
import com.linkedin.pinot.common.data.Schema;
import com.linkedin.pinot.core.data.GenericRow;
import com.linkedin.pinot.core.data.extractors.FieldExtractor;


public class AvroRecordReader implements RecordReader {
  private static final String COMMA = ",";

  private String _fileName = null;
  private DataFileStream<GenericRecord> _dataStream = null;
  private FieldExtractor _schemaExtractor = null;

  private final GenericRow _genericRow = new GenericRow();
  private final Map<String, Object> _fieldMap = new HashMap<String, Object>();

  public AvroRecordReader(final FieldExtractor fieldExtractor, String filePath) throws Exception {
    _schemaExtractor = fieldExtractor;
    _fileName = filePath;
    init();
  }

  @Override
  public void init() throws Exception {
    final File file = new File(_fileName);
    if (!file.exists()) {
      throw new FileNotFoundException("File is not existed!");
    }
    //_schemaExtractor = FieldExtractorFactory.get(_dataReaderSpec);
    if (_fileName.endsWith("gz"))
      _dataStream = new DataFileStream<GenericRecord>(new GZIPInputStream(new FileInputStream(file)), new GenericDatumReader<GenericRecord>());
    else
      _dataStream = new DataFileStream<GenericRecord>(new FileInputStream(file), new GenericDatumReader<GenericRecord>());

    updateSchema(_schemaExtractor.getSchema());
  }

  @Override
  public boolean hasNext() {
    return _dataStream.hasNext();
  }

  @Override
  public GenericRow next() {
    return _schemaExtractor.transform(getGenericRow(_dataStream.next()));
  }

  private GenericRow getGenericRow(GenericRecord rawRecord) {
    for (final Field field : _dataStream.getSchema().getFields()) {
      Object value = rawRecord.get(field.name());
      if (value instanceof Utf8) {
        value = ((Utf8) value).toString();
      }
      if (value instanceof Array) {
        value = transformAvroArrayToObjectArray((Array) value);
      }
      _fieldMap.put(field.name(), value);
    }
    _genericRow.init(_fieldMap);
    return _genericRow;
  }

  @Override
  public void close() throws IOException {
    _dataStream.close();
  }

  @Override
  public Schema getSchema() {
    return _schemaExtractor.getSchema();
  }

  private void updateSchema(Schema schema) {
    for (final FieldSpec fieldSpec : schema.getAllFieldSpecs()) {
      fieldSpec.setDataType(getColumnType(_dataStream.getSchema().getField(fieldSpec.getName())));
      fieldSpec.setSingleValueField(isSingleValueField(_dataStream.getSchema().getField(fieldSpec.getName())));
      schema.addSchema(fieldSpec.getName(), fieldSpec);
    }
  }

  private static boolean isSingleValueField(Field field) {
    org.apache.avro.Schema fieldSchema = field.schema();
    fieldSchema = extractSchemaFromUnionIfNeeded(fieldSchema);

    final Type type = fieldSchema.getType();
    if (type == Type.ARRAY) {
      return false;
    }
    return true;
  }

  public static DataType getColumnType(Field field) {
    org.apache.avro.Schema fieldSchema = field.schema();
    fieldSchema = extractSchemaFromUnionIfNeeded(fieldSchema);

    final Type type = fieldSchema.getType();
    if (type == Type.ARRAY) {
      org.apache.avro.Schema elementSchema = extractSchemaFromUnionIfNeeded(fieldSchema.getElementType());
      if (elementSchema.getType() == Type.RECORD) {
        if (elementSchema.getFields().size() == 1) {
          elementSchema = elementSchema.getFields().get(0).schema();
        } else {
          throw new RuntimeException("More than one schema in Multi-value column!");
        }
        elementSchema = extractSchemaFromUnionIfNeeded(elementSchema);
      }
      return DataType.valueOf(elementSchema.getType());
    } else {
      return DataType.valueOf(type);
    }
  }

  private static org.apache.avro.Schema extractSchemaFromUnionIfNeeded(org.apache.avro.Schema fieldSchema) {
    if ((fieldSchema).getType() == Type.UNION) {
      fieldSchema = ((org.apache.avro.Schema) CollectionUtils.find(fieldSchema.getTypes(), new Predicate() {
        @Override
        public boolean evaluate(Object object) {
          return ((org.apache.avro.Schema) object).getType() != Type.NULL;
        }
      }));
    }
    return fieldSchema;
  }

  public static Object[] transformAvroArrayToObjectArray(Array arr) {
    if (arr == null) {
      return new Object[0];
    }
    final Object[] ret = new Object[arr.size()];
    final Iterator iterator = arr.iterator();
    int i = 0;
    while (iterator.hasNext()) {
      Object value = iterator.next();
      if (value instanceof Record) {
        value = ((Record) value).get(0);
      }
      if (value instanceof Utf8) {
        value = ((Utf8) value).toString();
      }
      ret[i++] = value;
    }
    return ret;
  }

  @Override
  public void rewind() throws Exception {
    _dataStream.close();
    _dataStream = null;
    init();
  }

}
