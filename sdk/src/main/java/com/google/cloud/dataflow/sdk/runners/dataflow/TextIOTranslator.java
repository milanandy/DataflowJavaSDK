/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.runners.dataflow;

import com.google.api.client.util.Preconditions;
import com.google.cloud.dataflow.sdk.io.ShardNameTemplate;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.runners.DataflowPipelineTranslator.TransformTranslator;
import com.google.cloud.dataflow.sdk.runners.DataflowPipelineTranslator.TranslationContext;
import com.google.cloud.dataflow.sdk.util.GcsUtil;
import com.google.cloud.dataflow.sdk.util.PropertyNames;
import com.google.cloud.dataflow.sdk.util.WindowedValue;
import com.google.cloud.dataflow.sdk.util.gcsfs.GcsPath;

/**
 * TextIO transform support code for the Dataflow backend.
 */
public class TextIOTranslator {

  /**
   * Implements TextIO Read translation for the Dataflow backend.
   */
  public static class ReadTranslator implements TransformTranslator<TextIO.Read.Bound> {
    @Override
    public void translate(
        TextIO.Read.Bound transform,
        TranslationContext context) {
      translateReadHelper(transform, context);
    }

    private <T> void translateReadHelper(
        TextIO.Read.Bound<T> transform,
        TranslationContext context) {
      if (context.getPipelineOptions().isStreaming()) {
        throw new IllegalArgumentException("TextIO not supported in streaming mode.");
      }

      // Only GCS paths are permitted for filepatterns in the DataflowPipelineRunner.
      GcsPath gcsPath = GcsPath.fromUri(transform.getFilepattern());
      // Furthermore, on the service there is currently a limitation
      // that the first wildcard character must occur after the last
      // delimiter, and that the delimiter is fixed to '/'
      if (!GcsUtil.GCS_READ_PATTERN.matcher(gcsPath.getObject()).matches()) {
        throw new IllegalArgumentException(
            "Unsupported wildcard usage in \"" + gcsPath + "\": "
            + " all wildcards must occur after the final '/' delimiter.");
      }

      context.addStep(transform, "ParallelRead");
      // TODO: How do we want to specify format and
      // format-specific properties?
      context.addInput(PropertyNames.FORMAT, "text");
      context.addInput(PropertyNames.FILEPATTERN, gcsPath);
      context.addValueOnlyOutput(PropertyNames.OUTPUT, transform.getOutput());
      context.addInput(PropertyNames.VALIDATE_SOURCE, transform.needsValidation());

      // TODO: Orderedness?
    }
  }

  /**
   * Implements TextIO Write translation for the Dataflow backend.
   */
  public static class WriteTranslator implements TransformTranslator<TextIO.Write.Bound> {
    @Override
    public void translate(
        TextIO.Write.Bound transform,
        TranslationContext context) {
      translateWriteHelper(transform, context);
    }

    private <T> void translateWriteHelper(
        TextIO.Write.Bound<T> transform,
        TranslationContext context) {
      if (context.getPipelineOptions().isStreaming()) {
        throw new IllegalArgumentException("TextIO not supported in streaming mode.");
      }

      // Only GCS paths are permitted for filepatterns in the DataflowPipelineRunner.
      GcsPath gcsPath = GcsPath.fromUri(transform.getFilenamePrefix());
      context.addStep(transform, "ParallelWrite");
      context.addInput(PropertyNames.PARALLEL_INPUT, transform.getInput());

      // TODO: drop this check when server supports alternative templates.
      switch (transform.getShardTemplate()) {
        case ShardNameTemplate.INDEX_OF_MAX:
          break;  // supported by server
        case "":
          // Empty shard template allowed - forces single output.
          Preconditions.checkArgument(transform.getNumShards() <= 1,
              "Num shards must be <= 1 when using an empty sharding template");
          break;
        default:
          throw new UnsupportedOperationException("Shard template "
              + transform.getShardTemplate()
              + " not yet supported by Dataflow service");
      }

      // TODO: How do we want to specify format and
      // format-specific properties?
      context.addInput(PropertyNames.FORMAT, "text");
      context.addInput(PropertyNames.FILENAME_PREFIX, gcsPath);
      context.addInput(PropertyNames.SHARD_NAME_TEMPLATE,
          transform.getShardNameTemplate());
      context.addInput(PropertyNames.FILENAME_SUFFIX, transform.getFilenameSuffix());

      long numShards = transform.getNumShards();
      if (numShards > 0) {
        context.addInput(PropertyNames.NUM_SHARDS, numShards);
      }

      context.addEncodingInput(
          WindowedValue.getValueOnlyCoder(transform.getCoder()));
    }
  }
}
