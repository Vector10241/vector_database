/*
 * Copyright 2021 DataCanvas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dingodb.exec.operator.params;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.dingodb.codec.CodecService;
import io.dingodb.codec.KeyValueCodec;
import io.dingodb.common.CommonId;
import io.dingodb.common.CoprocessorV2;
import io.dingodb.common.partition.RangeDistribution;
import io.dingodb.common.type.TupleMapping;
import io.dingodb.common.util.ByteArrayUtils;
import io.dingodb.exec.dag.Vertex;
import io.dingodb.exec.expr.DingoCompileContext;
import io.dingodb.exec.expr.DingoRelConfig;
import io.dingodb.exec.expr.SqlExpr;
import io.dingodb.exec.utils.SchemaWrapperUtils;
import io.dingodb.expr.coding.CodingFlag;
import io.dingodb.expr.coding.RelOpCoder;
import io.dingodb.expr.rel.RelOp;
import io.dingodb.expr.runtime.type.TupleType;
import io.dingodb.meta.entity.IndexTable;
import io.dingodb.meta.entity.Table;
import lombok.Getter;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.NavigableMap;
import java.util.stream.Collectors;

@Getter
@JsonTypeName("txn_partVector")
@JsonPropertyOrder({
    "tableId", "part", "schema", "keyMapping", "filter", "selection", "indexId", "indexRegionId"
})
public class TxnPartVectorParam extends FilterProjectSourceParam {
    private KeyValueCodec codec;
    private final Table table;

    private final NavigableMap<ByteArrayUtils.ComparableByteArray, RangeDistribution> distributions;
    private final CommonId indexId;
    private final Float[] floatArray;
    private final int topN;
    private final Map<String, Object> parameterMap;
    private final IndexTable indexTable;

    private final RelOp relOp;

    @JsonProperty("pushDown")
    private final boolean pushDown;

    @JsonProperty("scanTs")
    private final long scanTs;
    @JsonProperty("isolationLevel")
    private final int isolationLevel;
    @JsonProperty("timeOut")
    private final long timeOut;
    private CoprocessorV2 coprocessor = null;
    private final boolean isLookUp;

    public TxnPartVectorParam(
        CommonId partId,
        SqlExpr filter,
        TupleMapping selection,
        Table table,
        NavigableMap<ByteArrayUtils.ComparableByteArray, RangeDistribution> distributions,
        Float[] floatArray,
        int topN,
        Map<String, Object> parameterMap,
        Table indexTable,
        RelOp relOp,
        boolean pushDown,
        boolean isLookUp,
        long scanTs,
        int isolationLevel,
        long timeOut
    ) {
        super(table.tableId, partId, table.tupleType(), filter, selection, table.keyMapping());
        this.table = table;
        this.distributions = distributions;
        this.indexId = indexTable.tableId;
        this.floatArray = floatArray;
        this.topN = topN;
        this.parameterMap = parameterMap;
        this.indexTable = (IndexTable) indexTable;
        this.pushDown = pushDown;
        this.scanTs = scanTs;
        this.isolationLevel = isolationLevel;
        this.timeOut = timeOut;
        this.isLookUp = isLookUp;
        this.relOp = relOp;
    }

    @Override
    public void init(Vertex vertex) {
        super.init(vertex);
        if (pushDown) {
            CoprocessorV2.CoprocessorV2Builder builder = CoprocessorV2.builder();
            if (selection != null) {
                builder.selection(selection.stream().boxed().collect(Collectors.toList()));
                //selection = null;
            }
            relOp.compile(new DingoCompileContext(
                (TupleType) schema.getType(),
                (TupleType) vertex.getParasType().getType()
            ), new DingoRelConfig());
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            if (RelOpCoder.INSTANCE.visit(relOp, os) == CodingFlag.OK) {
                builder.relExpr(os.toByteArray());
            }
//            if (filter != null) {
//                byte[] code = filter.getCoding(filterInputSchema, vertex.getParasType());
//                if (code != null) {
//                    builder.relExpr(code);
//                    filter = null;
//                }
//            }
            builder.schemaVersion(table.getVersion());
            builder.originalSchema(SchemaWrapperUtils.buildSchemaWrapper(indexTable.tupleType(), indexTable.keyMapping(), indexTable.getTableId().seq));
            coprocessor = builder.build();
        }
        codec = CodecService.getDefault().createKeyValueCodec(schema, keyMapping);
    }

}
