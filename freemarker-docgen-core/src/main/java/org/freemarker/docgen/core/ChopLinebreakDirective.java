/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.freemarker.docgen.core;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import freemarker.core.Environment;
import freemarker.core.variables.UserDirective;
import freemarker.core.variables.UserDirectiveBody;
import freemarker.core.variables.EvaluationException;
import freemarker.template.TemplateException;

/**
 * Similar to <code>${capturedContent?chopLinebreak}</code>, but it's "streaming", which is important if we want the
 * partial output when there's an exception in the mid of generating the content.
 */
class ChopLinebreakDirective extends FilterDirective {
    static final ChopLinebreakDirective INSTANCE = new ChopLinebreakDirective();

    private ChopLinebreakDirective() {
    }

    @Override
    protected Writer wrapWriter(Writer out) {
        return new ChopLinebreakWriter(out);
    }
}
