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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.text.StringEscapeUtils;

import freemarker.core.Environment;
import freemarker.core.HTMLOutputFormat;
import freemarker.core.NonStringException;
import freemarker.core.TemplateHTMLOutputModel;
import freemarker.core.TemplateValueFormatException;
import freemarker.template.TemplateBooleanModel;
import freemarker.template.TemplateDateModel;
import freemarker.template.TemplateDirectiveBody;
import freemarker.template.TemplateDirectiveModel;
import freemarker.template.TemplateException;
import freemarker.template.TemplateHashModel;
import freemarker.template.TemplateModel;
import freemarker.template.TemplateNumberModel;
import freemarker.template.TemplateScalarModel;
import freemarker.template.utility.ClassUtil;
import freemarker.template.utility.StringUtil;

public class PrintTextWithDocgenSubstitutionsDirective implements TemplateDirectiveModel {

    private static final String PARAM_TEXT = "text";
    private static final String DOCGEN_TAG_START = "[docgen";
    private static final String DOCGEN_TAG_END = "]";
    private static final String INSERT_FILE = "insertFile";

    private final Transform transform;

    public PrintTextWithDocgenSubstitutionsDirective(Transform transform) {
        this.transform = transform;
    }

    @Override
    public void execute(Environment env, Map params, TemplateModel[] loopVars, TemplateDirectiveBody body)
            throws TemplateException, IOException {
        String text = null;
        for (Map.Entry<String, TemplateModel> entry : ((Map<String, TemplateModel>) params).entrySet()) {
            String paramName = entry.getKey();
            TemplateModel paramValue = entry.getValue();
            if (paramValue != null) {
                if (PARAM_TEXT.equals(paramName)) {
                    if (!(paramValue instanceof TemplateScalarModel)) {
                        throw new NonStringException("The \"" + PARAM_TEXT + "\" argument must be a string!", env);
                    }
                    text = ((TemplateScalarModel) paramValue).getAsString();
                } else {
                    throw new TemplateException("Unsupported parameter: " + StringUtil.jQuote(paramName), env);
                }
            }
        }
        if (text == null) {
            throw new TemplateException("Missing required \"" + PARAM_TEXT + "\" argument", env);
        }

        if (loopVars.length != 0) {
            throw new TemplateException("Directive doesn't support loop variables", env);
        }

        if (body != null) {
            throw new TemplateException("Directive doesn't support nested content", env);
        }

        new DocgenSubstitutionInterpreter(text, env).execute();
    }

    private class DocgenSubstitutionInterpreter {
        private final String text;
        private final Environment env;
        private final Writer out;
        private int cursor;
        private int lastDocgenTagStart;

        public DocgenSubstitutionInterpreter(String text, Environment env) {
            this.text = text;
            this.env = env;
            this.out = env.getOut();
        }

        private void execute() throws TemplateException, IOException {
            int lastUnprintedIdx = 0;
            parseText: while (true) {
                cursor = findNextDocgenTagStart(lastUnprintedIdx);
                if (cursor == -1) {
                    break parseText;
                } else {
                    lastDocgenTagStart = cursor;
                }

                HTMLOutputFormat.INSTANCE.output(text.substring(lastUnprintedIdx, cursor), out);
                lastUnprintedIdx = cursor;

                cursor += DOCGEN_TAG_START.length();
                skipRequiredToken(".");
                String subvarName = fetchRequiredVariableName();

                if (Transform.VAR_CUSTOM_VARIABLES.equals(subvarName)) {
                    skipRequiredToken(".");
                    String customVarName = fetchRequiredVariableName();
                    skipRequiredToken(DOCGEN_TAG_END);
                    lastUnprintedIdx = cursor;

                    insertCustomVariable(customVarName);
                } else if (INSERT_FILE.equals(subvarName)) {
                    skipWS();
                    String pathArg = fetchRequiredString();
                    String charsetArg = null;
                    String fromArg = null;
                    String toArg = null;
                    String toIfPresentArg = null;
                    Set<String> paramNamesSeen = new HashSet<>();
                    while (skipWS()) {
                        String paramName = fetchOptionalVariableName();
                        skipRequiredToken("=");
                        String paramValue = StringEscapeUtils.unescapeXml(fetchRequiredString());
                        if (!paramNamesSeen.add(paramName)) {
                            throw new TemplateException(
                                    "Duplicate " + StringUtil.jQuote(INSERT_FILE)
                                            +  " parameter " + StringUtil.jQuote(paramName) + ".", env);
                        }
                        if (paramName.equals("charset")) {
                            charsetArg = paramValue;
                        } else if (paramName.equals("from")) {
                            fromArg = paramValue;
                        } else if (paramName.equals("to")) {
                            toArg = paramValue;
                        } else if (paramName.equals("toIfPresent")) {
                            toIfPresentArg = paramValue;
                        } else {
                            throw new TemplateException(
                                    "Unsupported " + StringUtil.jQuote(INSERT_FILE)
                                            +  " parameter " + StringUtil.jQuote(paramName) + ".", env);
                        }
                    }
                    skipRequiredToken(DOCGEN_TAG_END);
                    lastUnprintedIdx = cursor;

                    insertFile(pathArg, charsetArg, fromArg, toArg, toIfPresentArg);
                } else {
                    throw new TemplateException(
                            "Unsupported docgen subvariable " + StringUtil.jQuote(subvarName) + ".", env);
                }

            }
            HTMLOutputFormat.INSTANCE.output(text.substring(lastUnprintedIdx, text.length()), out);
        }

        private void insertCustomVariable(String customVarName) throws TemplateException, IOException {
            TemplateHashModel customVariables =
                    Objects.requireNonNull(
                            (TemplateHashModel) env.getVariable(Transform.VAR_CUSTOM_VARIABLES));
            TemplateModel customVarValue = customVariables.get(customVarName);
            if (customVarValue == null) {
                throw newErrorInDocgenTag(
                        "Docgen custom variable " + StringUtil.jQuote(customVarName)
                                + " wasn't defined or is null.");
            }

            printValue(customVarName, customVarValue);
        }

        /** Horrible hack to mimic ${var}; the public FreeMarker API should have something like this! */
        private void printValue(String varName, TemplateModel varValue) throws TemplateException,
                IOException {
            Object formattedValue;
            if (varValue instanceof TemplateNumberModel) {
                try {
                    formattedValue = env.getTemplateNumberFormat().format((TemplateNumberModel) varValue);
                } catch (TemplateValueFormatException e) {
                    throw newFormattingFailedException(varName, e);
                }
            } else if (varValue instanceof TemplateDateModel) {
                TemplateDateModel tdm = (TemplateDateModel) varValue;
                try {
                    formattedValue = env.getTemplateDateFormat(tdm.getDateType(), tdm.getAsDate().getClass())
                            .format(tdm);
                } catch (TemplateValueFormatException e) {
                    throw newFormattingFailedException(varName, e);
                }
            } else if (varValue instanceof TemplateScalarModel) {
                formattedValue = ((TemplateScalarModel) varValue).getAsString();
            } else if (varValue instanceof TemplateBooleanModel) {
                String[] booleanStrValues = env.getBooleanFormat().split(",");
                formattedValue = ((TemplateBooleanModel) varValue).getAsBoolean()
                        ? booleanStrValues[0] : booleanStrValues[1];
            } else {
                throw new TemplateException(
                        "Docgen custom variable " + StringUtil.jQuote(varName)
                                + " has an unsupported type: "
                                + ClassUtil.getFTLTypeDescription(varValue),
                        env);
            }
            if (formattedValue instanceof String) {
                HTMLOutputFormat.INSTANCE.output((String) formattedValue, out);
            } else {
                HTMLOutputFormat.INSTANCE.output((TemplateHTMLOutputModel) formattedValue, out);
            }
        }

        private void insertFile(String pathArg, String charsetArg, String fromArg,
                String toArg, String toIfPresentArg)
                throws TemplateException, IOException {
            int slashIndex = pathArg.indexOf("/");
            String symbolicNameStep = slashIndex != -1 ? pathArg.substring(0, slashIndex) : pathArg;
            if (!symbolicNameStep.startsWith("@") || symbolicNameStep.length() < 2) {
                throw newErrorInDocgenTag("Path argument must start with @<symbolicName>/, "
                        + " where <symbolicName> is in " + transform.getInsertableFiles().keySet() + ".");
            }
            String symbolicName = symbolicNameStep.substring(1);
            Path symbolicNamePath = transform.getInsertableFiles().get(symbolicName);
            if (symbolicNamePath == null) {
                throw newErrorInDocgenTag("Symbolic insertable file name "
                        + StringUtil.jQuote(symbolicName) + " is not amongst the defined names: "
                        + transform.getInsertableFiles().keySet());
            }
            symbolicNamePath = symbolicNamePath.toAbsolutePath().normalize();
            Path resolvedFilePath = slashIndex != -1
                    ? symbolicNamePath.resolve(pathArg.substring(slashIndex + 1))
                    : symbolicNamePath;
            resolvedFilePath = resolvedFilePath.normalize();
            if (!resolvedFilePath.startsWith(symbolicNamePath)) {
                throw newErrorInDocgenTag("Resolved path ("
                        + resolvedFilePath + ") is not inside the base path ("
                        + symbolicNamePath + ").");
            }
            if (!Files.isRegularFile(resolvedFilePath)) {
                throw newErrorInDocgenTag("Not an existing file: " + resolvedFilePath);
            }

            Charset charset;
            if (charsetArg != null) {
                try {
                    charset = Charset.forName(charsetArg);
                } catch (UnsupportedCharsetException e) {
                    throw newErrorInDocgenTag("Unsupported charset: " + charsetArg);
                }
            } else {
                charset = StandardCharsets.UTF_8;
            }

            try (InputStream in = Files.newInputStream(resolvedFilePath)) {
                String fileContent = IOUtils.toString(in, charset);
                String fileExt = FilenameUtils.getExtension(resolvedFilePath.getFileName().toString());
                if (fileExt != null && fileExt.toLowerCase().startsWith("ftl")) {
                    fileContent = removeFTLCopyrightComment(fileContent);
                }

                if (fromArg != null) {
                    boolean optional;
                    String fromArgCleaned;
                    if (fromArg.startsWith("?")) {
                        optional = true;
                        fromArgCleaned = fromArg.substring(1);
                    } else {
                        optional = false;
                        fromArgCleaned = fromArg;
                    }
                    Pattern from;
                    try {
                        from = Pattern.compile(fromArgCleaned);
                    } catch (PatternSyntaxException e) {
                        throw newErrorInDocgenTag("Invalid regular expression: " + fromArgCleaned);
                    }
                    Matcher matcher = from.matcher(fileContent);
                    if (matcher.find()) {
                        String remaining = fileContent.substring(matcher.start());
                        fileContent = "[\u2026]"
                                + (remaining.startsWith("\n") || remaining.startsWith("\r") ? "" : "\n")
                                + remaining;
                    } else {
                        if (!optional) {
                            throw newErrorInDocgenTag(
                                    "Regular expression has no match in the file content: " + fromArg);
                        }
                    }
                }

                String toStr;
                boolean toPresenceOptional;
                if (toArg != null) {
                    if (toIfPresentArg != null) {
                        throw newErrorInDocgenTag(
                                "Can't use both \"to\" and \"toIfPresent\" argument.");
                    }
                    toStr = toArg;
                    toPresenceOptional = false;
                } else if (toIfPresentArg != null) {
                    toStr = toIfPresentArg;
                    toPresenceOptional = true;
                } else {
                    toStr = null;
                    toPresenceOptional = false;
                }
                if (toStr != null) {
                    Pattern to;
                    try {
                        to = Pattern.compile(toStr);
                    } catch (PatternSyntaxException e) {
                        throw newErrorInDocgenTag("Invalid regular expression: " + toStr);
                    }
                    Matcher matcher = to.matcher(fileContent);
                    if (matcher.find()) {
                        String remaining = fileContent.substring(0, matcher.start());
                        fileContent = remaining
                                + (remaining.endsWith("\n") || remaining.endsWith("\r") ? "" : "\n")
                                + "[\u2026]";
                    } else {
                        if (!toPresenceOptional) {
                            throw newErrorInDocgenTag(
                                    "Regular expression has no match in the file content: " + toStr);
                        }
                    }
                }

                HTMLOutputFormat.INSTANCE.output(fileContent, out);
            }
        }

        private TemplateException newFormattingFailedException(String customVarName, TemplateValueFormatException e) {
            return new TemplateException(
                    "Formatting failed for Docgen custom variable "
                            + StringUtil.jQuote(customVarName),
                    e, env);
        }

        private int findNextDocgenTagStart(int lastUnprintedIdx) {
            int startIdx = text.indexOf(DOCGEN_TAG_START, lastUnprintedIdx);
            if (startIdx == -1) {
                return -1;
            }
            int afterTagStartIdx = startIdx + DOCGEN_TAG_START.length();
            if (afterTagStartIdx < text.length()
                    && !Character.isJavaIdentifierPart(text.charAt(afterTagStartIdx))) {
                return startIdx;
            }
            return -1;
        }

        private boolean skipWS() {
            boolean found = false;
            while (cursor < text.length()) {
                if (Character.isWhitespace(text.charAt(cursor))) {
                    cursor++;
                    found = true;
                } else {
                    break;
                }
            }
            return found;
        }

        private void skipRequiredToken(String token) throws TemplateException {
            if (!skipOptionalToken(token)) {
                throw newUnexpectedTokenException(StringUtil.jQuote(token), env);
            }
        }

        private boolean skipOptionalToken(String token) throws TemplateException {
            skipWS();
            for (int i = 0; i < token.length(); i++) {
                char expectedChar = token.charAt(i);
                int lookAheadCursor = cursor + i;
                if (charAt(lookAheadCursor) != expectedChar) {
                    return false;
                }
            }
            cursor += token.length();
            skipWS();
            return true;
        }

        private String fetchRequiredVariableName() throws TemplateException {
            String varName = fetchOptionalVariableName();
            if (varName == null) {
                throw newUnexpectedTokenException("variable name", env);
            }
            return varName;
        }

        private String fetchOptionalVariableName() {
            if (!Character.isJavaIdentifierStart(charAt(cursor))) {
                return null;
            }
            int varNameStart = cursor;
            cursor++;
            while (Character.isJavaIdentifierPart(charAt(cursor))) {
                cursor++;
            }
            return text.substring(varNameStart, cursor);
        }

        private String fetchRequiredString() throws TemplateException {
            String result = fetchOptionalString();
            if (result == null) {
                throw newUnexpectedTokenException("string literal", env);
            }
            return result;
        }

        private String fetchOptionalString() throws TemplateException {
            char quoteChar = charAt(cursor);
            if (quoteChar != '"' && quoteChar != '\'') {
                return null;
            }
            cursor++;
            int stringStartIdx = cursor;
            while (cursor < text.length() && charAt(cursor) != quoteChar) {
                if (charAt(cursor) == '\\') {
                    throw new DocgenSubstitutionTemplateException(
                            "Backslash is currently not supported in string literal in Docgen tags.", env);
                }
                cursor++;
            }
            if (charAt(cursor) != quoteChar) {
                throw new DocgenSubstitutionTemplateException("Unclosed string literal in a Docgen tag.", env);
            }
            String result = text.substring(stringStartIdx, cursor);
            cursor++;
            return result;
        }

        private char charAt(int index) {
            return index < text.length() ? text.charAt(index) : 0;
        }

        private TemplateException newUnexpectedTokenException(String expectedTokenDesc, Environment env) {
            return new DocgenSubstitutionTemplateException(
                    "Expected " + expectedTokenDesc + " after this: " + text.substring(lastDocgenTagStart, cursor),
                    env);
        }

        private TemplateException newErrorInDocgenTag(String errorDetail) {
            return new DocgenSubstitutionTemplateException(
                    "\nError in docgen tag: " + text.substring(lastDocgenTagStart, cursor) + "\n" + errorDetail,
                    env);

        }
    }

    public static String removeFTLCopyrightComment(String ftl) {
        int copyrightPartIdx = ftl.indexOf("Licensed to the Apache Software Foundation");
        if (copyrightPartIdx == -1) {
            return ftl;
        }

        final int commentFirstIdx;
        final boolean squareBracketTagSyntax;
        {
            String ftlBeforeCopyright = ftl.substring(0, copyrightPartIdx);
            int abCommentStart = ftlBeforeCopyright.lastIndexOf("<#--");
            int sbCommentStart = ftlBeforeCopyright.lastIndexOf("[#--");
            squareBracketTagSyntax = sbCommentStart > abCommentStart;
            commentFirstIdx = squareBracketTagSyntax ? sbCommentStart : abCommentStart;
            if (commentFirstIdx == -1) {
                throw new AssertionError("Can't find copyright comment start");
            }
        }

        final int commentLastIdx;
        {
            int commentEndStart = ftl.indexOf(squareBracketTagSyntax ? "--]" : "-->", copyrightPartIdx);
            if (commentEndStart == -1) {
                throw new AssertionError("Can't find copyright comment end");
            }
            commentLastIdx = commentEndStart + 2;
        }

        final int afterCommentNLChars;
        if (commentLastIdx + 1 < ftl.length()) {
            char afterCommentChar = ftl.charAt(commentLastIdx + 1);
            if (afterCommentChar == '\n' || afterCommentChar == '\r') {
                if (afterCommentChar == '\r' && commentLastIdx + 2 < ftl.length()
                        && ftl.charAt(commentLastIdx + 2) == '\n') {
                    afterCommentNLChars = 2;
                } else {
                    afterCommentNLChars = 1;
                }
            } else {
                afterCommentNLChars = 0;
            }
        } else {
            afterCommentNLChars = 0;
        }

        return ftl.substring(0, commentFirstIdx) + ftl.substring(commentLastIdx + afterCommentNLChars + 1);
    }

}
