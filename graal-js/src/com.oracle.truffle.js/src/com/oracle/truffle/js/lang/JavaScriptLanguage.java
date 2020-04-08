/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;

import org.graalvm.options.OptionDescriptor;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionValues;
import org.graalvm.polyglot.Context;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Scope;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleStackTrace;
import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.debug.DebuggerTags;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExecutableNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.ScriptNode;
import com.oracle.truffle.js.nodes.access.InitErrorObjectNodeFactory;
import com.oracle.truffle.js.nodes.control.TryCatchNode;
import com.oracle.truffle.js.nodes.function.FunctionRootNode;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.BinaryOperationTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.BuiltinRootTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ControlFlowBlockTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ControlFlowBranchTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ControlFlowRootTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.DeclareTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.EvalCallTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.FunctionCallTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.InputNodeTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.LiteralTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ObjectAllocationTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ReadElementTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ReadPropertyTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ReadVariableTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.UnaryOperationTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.WriteElementTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.WritePropertyTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.WriteVariableTag;
import com.oracle.truffle.js.nodes.interop.ExportValueNode;
import com.oracle.truffle.js.runtime.AbstractJavaScriptLanguage;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSAgent;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSConfig;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSContextOptions;
import com.oracle.truffle.js.runtime.JSEngine;
import com.oracle.truffle.js.runtime.JSException;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.objects.JSScope;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.truffleinterop.JavaScriptLanguageView;

@ProvidedTags({
                StandardTags.StatementTag.class,
                StandardTags.RootTag.class,
                StandardTags.RootBodyTag.class,
                StandardTags.ExpressionTag.class,
                StandardTags.CallTag.class,
                StandardTags.ReadVariableTag.class,
                StandardTags.WriteVariableTag.class,
                StandardTags.TryBlockTag.class,
                DebuggerTags.AlwaysHalt.class,
                // Expressions
                ObjectAllocationTag.class,
                BinaryOperationTag.class,
                UnaryOperationTag.class,
                WriteVariableTag.class,
                ReadElementTag.class,
                WriteElementTag.class,
                ReadPropertyTag.class,
                WritePropertyTag.class,
                ReadVariableTag.class,
                LiteralTag.class,
                FunctionCallTag.class,
                // Statements and builtins
                BuiltinRootTag.class,
                EvalCallTag.class,
                ControlFlowRootTag.class,
                ControlFlowBlockTag.class,
                ControlFlowBranchTag.class,
                DeclareTag.class,
                // Other
                InputNodeTag.class,
})

@TruffleLanguage.Registration(id = JavaScriptLanguage.ID, name = JavaScriptLanguage.NAME, implementationName = JavaScriptLanguage.IMPLEMENTATION_NAME, characterMimeTypes = {
                JavaScriptLanguage.APPLICATION_MIME_TYPE,
                JavaScriptLanguage.TEXT_MIME_TYPE,
                JavaScriptLanguage.MODULE_MIME_TYPE}, defaultMimeType = JavaScriptLanguage.APPLICATION_MIME_TYPE, contextPolicy = TruffleLanguage.ContextPolicy.SHARED, dependentLanguages = "regex", fileTypeDetectors = JSFileTypeDetector.class)
public final class JavaScriptLanguage extends AbstractJavaScriptLanguage {
    public static final String TEXT_MIME_TYPE = "text/javascript";
    public static final String APPLICATION_MIME_TYPE = "application/javascript";
    public static final String MODULE_MIME_TYPE = "application/javascript+module";
    public static final String SCRIPT_SOURCE_NAME_SUFFIX = ".js";
    public static final String MODULE_SOURCE_NAME_SUFFIX = ".mjs";
    public static final String INTERNAL_SOURCE_URL_PREFIX = "internal:";

    public static final String NAME = "JavaScript";
    public static final String IMPLEMENTATION_NAME = "GraalVM JavaScript";
    public static final String ID = "js";

    private volatile JSContext languageContext;
    private volatile boolean multiContext;

    private final Assumption promiseJobsQueueEmptyAssumption;

    public static final OptionDescriptors OPTION_DESCRIPTORS;
    static {
        ArrayList<OptionDescriptor> options = new ArrayList<>();
        JSContextOptions.describeOptions(options);
        OPTION_DESCRIPTORS = OptionDescriptors.create(options);
        ensureErrorClassesInitialized();
        checkUnknownOptions();
    }

    public JavaScriptLanguage() {
        this.promiseJobsQueueEmptyAssumption = Truffle.getRuntime().createAssumption("PromiseJobsQueueEmpty");
    }

    @TruffleBoundary
    @Override
    public CallTarget parse(ParsingRequest parsingRequest) {
        Source source = parsingRequest.getSource();
        List<String> argumentNames = parsingRequest.getArgumentNames();
        if (argumentNames == null || argumentNames.isEmpty()) {
            final JSContext context = getJSContext();
            final ScriptNode program = parseScript(context, source, "", "", false);

            if (context.isOptionParseOnly()) {
                return createEmptyScript(context).getCallTarget();
            }

            RootNode rootNode = new RootNode(this) {
                @Child private DirectCallNode directCallNode = DirectCallNode.create(program.getCallTarget());
                @Child private ExportValueNode exportValueNode = ExportValueNode.create();
                @CompilationFinal private ContextReference<JSRealm> contextReference;

                @Override
                public Object execute(VirtualFrame frame) {
                    if (contextReference == null) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        contextReference = lookupContextReference(JavaScriptLanguage.class);
                    }
                    JSRealm realm = contextReference.get();
                    assert realm.getContext() == context : "unexpected JSContext";
                    try {
                        interopBoundaryEnter(realm);
                        Object result = directCallNode.call(program.argumentsToRun(realm));
                        return exportValueNode.execute(result);
                    } finally {
                        interopBoundaryExit(realm);
                    }
                }

                @Override
                public boolean isInternal() {
                    return true;
                }
            };
            return Truffle.getRuntime().createCallTarget(rootNode);
        } else {
            RootNode rootNode = parseWithArgumentNames(source, argumentNames);
            return Truffle.getRuntime().createCallTarget(rootNode);
        }
    }

    @TruffleBoundary
    private static ScriptNode createEmptyScript(JSContext context) {
        return ScriptNode.fromFunctionData(context, JSFunction.createEmptyFunctionData(context));
    }

    @Override
    protected ExecutableNode parse(InlineParsingRequest request) throws Exception {
        final Source source = request.getSource();
        final MaterializedFrame requestFrame = request.getFrame();
        final JSContext context = getJSContext();
        final boolean strict = isStrictLocation(request.getLocation());
        final ExecutableNode executableNode = new ExecutableNode(this) {
            @Child private JavaScriptNode expression = insert(parseInlineScript(context, source, requestFrame, strict));
            @Child private ExportValueNode exportValueNode = ExportValueNode.create();

            @Override
            public Object execute(VirtualFrame frame) {
                assert JavaScriptLanguage.getCurrentJSRealm().getContext() == context : "unexpected JSContext";
                Object result = expression.execute(frame);
                return exportValueNode.execute(result);
            }
        };
        return executableNode;
    }

    private static boolean isStrictLocation(Node location) {
        if (location != null) {
            RootNode rootNode = location.getRootNode();
            if (rootNode instanceof FunctionRootNode) {
                return ((FunctionRootNode) rootNode).getFunctionData().isStrict();
            }
        }
        return true;
    }

    private RootNode parseWithArgumentNames(Source source, List<String> argumentNames) {
        StringBuilder prolog = new StringBuilder();
        prolog.append("'use strict';");
        prolog.append("(function");
        prolog.append(" (");
        for (int i = 0; i < argumentNames.size(); i++) {
            if (i != 0) {
                prolog.append(", ");
            }
            prolog.append(argumentNames.get(i));
        }
        prolog.append(") {\n");

        ScriptNode program = parseScript(getJSContext(), source, prolog.toString(), "})", true);

        return new RootNode(this) {
            @CompilationFinal private ContextReference<JSRealm> contextReference;

            @Override
            public Object execute(VirtualFrame frame) {
                if (contextReference == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    contextReference = lookupContextReference(JavaScriptLanguage.class);
                }
                JSRealm realm = contextReference.get();
                return executeImpl(realm, frame.getArguments());
            }

            @TruffleBoundary
            private Object executeImpl(JSRealm realm, Object[] arguments) {
                Object function = program.run(realm);
                return JSRuntime.jsObjectToJavaObject(JSFunction.call(JSArguments.create(Undefined.instance, function, arguments)));
            }
        };
    }

    @TruffleBoundary
    protected static ScriptNode parseScript(JSContext context, Source code, String prolog, String epilog, boolean alwaysReturnValue) {
        boolean profileTime = context.getContextOptions().isProfileTime();
        long startTime = profileTime ? System.nanoTime() : 0L;
        try {
            return context.getEvaluator().parseScript(context, code, prolog, epilog, alwaysReturnValue);
        } finally {
            if (profileTime) {
                context.getTimeProfiler().printElapsed(startTime, "parsing " + code.getName());
            }
        }
    }

    @TruffleBoundary
    protected static JavaScriptNode parseInlineScript(JSContext context, Source code, MaterializedFrame lexicalContextFrame, boolean strict) {
        boolean profileTime = context.getContextOptions().isProfileTime();
        long startTime = profileTime ? System.nanoTime() : 0L;
        try {
            return context.getEvaluator().parseInlineScript(context, code, lexicalContextFrame, strict);
        } finally {
            if (profileTime) {
                context.getTimeProfiler().printElapsed(startTime, "parsing " + code.getName());
            }
        }
    }

    @Override
    protected JSRealm createContext(Env env) {
        JSContext context = languageContext;
        if (context == null) {
            context = initLanguageContext(env);
        }
        JSRealm realm = context.createRealm(env);

        if (env.out() != realm.getOutputStream()) {
            realm.setOutputWriter(null, env.out());
        }
        if (env.err() != realm.getErrorStream()) {
            realm.setErrorWriter(null, env.err());
        }

        return realm;
    }

    private synchronized JSContext initLanguageContext(Env env) {
        JSContext curContext = languageContext;
        if (curContext != null) {
            assert curContext.getContextOptions().equals(JSContextOptions.fromOptionValues(env.getOptions()));
            return curContext;
        }
        JSContext newContext = newJSContext(env);
        languageContext = newContext;
        return newContext;
    }

    private JSContext newJSContext(Env env) {
        return JSEngine.createJSContext(this, env);
    }

    @Override
    protected void initializeContext(JSRealm realm) {
        realm.initialize();
    }

    @Override
    protected boolean patchContext(JSRealm realm, Env newEnv) {
        assert realm.getContext().getLanguage() == this;

        if (optionsAllowPreInitializedContext(realm.getEnv(), newEnv) && realm.patchContext(newEnv)) {
            return true;
        } else {
            languageContext = null;
            return false;
        }
    }

    /**
     * Options which can be patched without throwing away the pre-initialized context.
     */
    private static final OptionKey<?>[] PREINIT_CONTEXT_PATCHABLE_OPTIONS = {
                    JSContextOptions.ARRAY_SORT_INHERITED,
                    JSContextOptions.TIMER_RESOLUTION,
                    JSContextOptions.SHELL,
                    JSContextOptions.V8_COMPATIBILITY_MODE,
                    JSContextOptions.GLOBAL_PROPERTY,
                    JSContextOptions.GLOBAL_ARGUMENTS,
                    JSContextOptions.SCRIPTING,
                    JSContextOptions.DIRECT_BYTE_BUFFER,
                    JSContextOptions.INTL_402,
                    JSContextOptions.LOAD,
                    JSContextOptions.PRINT,
                    JSContextOptions.CONSOLE,
                    JSContextOptions.PERFORMANCE,
                    JSContextOptions.CLASS_FIELDS,
                    JSContextOptions.REGEXP_STATIC_RESULT,
    };

    /**
     * Check for options that differ from the expected options and do not support patching, in which
     * case we cannot use the pre-initialized context for faster startup.
     */
    private static boolean optionsAllowPreInitializedContext(Env preinitEnv, Env env) {
        OptionValues preinitOptions = preinitEnv.getOptions();
        OptionValues options = env.getOptions();
        if (!preinitOptions.hasSetOptions() && !options.hasSetOptions()) {
            return true;
        } else if (preinitOptions.equals(options)) {
            return true;
        } else {
            assert preinitOptions.getDescriptors().equals(options.getDescriptors());
            Collection<OptionKey<?>> ignoredOptions = Arrays.asList(PREINIT_CONTEXT_PATCHABLE_OPTIONS);
            for (OptionDescriptor descriptor : options.getDescriptors()) {
                OptionKey<?> key = descriptor.getKey();
                if (preinitOptions.hasBeenSet(key) || options.hasBeenSet(key)) {
                    if (ignoredOptions.contains(key)) {
                        continue;
                    }
                    if (!preinitOptions.get(key).equals(options.get(key))) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    @Override
    protected void disposeContext(JSRealm realm) {
        CompilerAsserts.neverPartOfCompilation();
        JSContext context = realm.getContext();
        JSContextOptions options = context.getContextOptions();
        if (options.isProfileTime() && options.isProfileTimePrintCumulative()) {
            context.getTimeProfiler().printCumulative();
        }
        realm.setGlobalObject(Undefined.instance);
    }

    @Override
    protected void initializeMultipleContexts() {
        multiContext = true;
    }

    @Override
    public boolean isMultiContext() {
        return multiContext;
    }

    @Override
    protected boolean areOptionsCompatible(OptionValues firstOptions, OptionValues newOptions) {
        return firstOptions.equals(newOptions);
    }

    @Override
    protected OptionDescriptors getOptionDescriptors() {
        return OPTION_DESCRIPTORS;
    }

    @Override
    protected boolean isVisible(JSRealm realm, Object value) {
        return (value != Undefined.instance);
    }

    @Override
    protected Object getLanguageView(JSRealm context, Object value) {
        return JavaScriptLanguageView.create(value);
    }

    @Override
    protected Iterable<Scope> findLocalScopes(JSRealm realm, Node node, Frame frame) {
        return JSScope.createLocalScopes(node, frame == null ? null : frame.materialize());
    }

    @Override
    protected Iterable<Scope> findTopScopes(JSRealm realm) {
        return JSScope.createGlobalScopes(realm);
    }

    public static JSContext getJSContext(Context context) {
        return getJSRealm(context).getContext();
    }

    public static JSRealm getJSRealm(Context context) {
        context.enter();
        try {
            context.initialize(ID);
            return getCurrentContext(JavaScriptLanguage.class);
        } finally {
            context.leave();
        }
    }

    @SuppressWarnings("static-method")
    public void interopBoundaryEnter(JSRealm realm) {
        realm.getAgent().interopBoundaryEnter();
    }

    public void interopBoundaryExit(JSRealm realm) {
        JSAgent agent = realm.getAgent();
        if (agent.interopBoundaryExit()) {
            if (!promiseJobsQueueEmptyAssumption.isValid()) {
                agent.processAllPromises();
            }
        }
    }

    public Assumption getPromiseJobsQueueEmptyAssumption() {
        return promiseJobsQueueEmptyAssumption;
    }

    public JSContext getJSContext() {
        return languageContext;
    }

    public boolean bindMemberFunctions() {
        return getJSContext().getContextOptions().bindMemberFunctions();
    }

    private static void ensureErrorClassesInitialized() {
        if (JSConfig.SubstrateVM) {
            return;
        }
        // Ensure error-related classes are initialized to avoid NoClassDefFoundError
        // during conversion of StackOverflowError to RangeError
        try {
            Class.forName(Errors.class.getName());
            Class.forName(JSException.class.getName());
            Class.forName(TruffleStackTrace.class.getName());
            Class.forName(TruffleStackTraceElement.class.getName());
            Class.forName(InitErrorObjectNodeFactory.DefineStackPropertyNodeGen.class.getName());
            Class.forName(TryCatchNode.GetErrorObjectNode.class.getName());
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static final String TRUFFLE_JS_OPTION_PREFIX = "truffle.js.";
    private static final String PARSER_OPTION_PREFIX = "parser.";

    private static void checkUnknownOptions() {
        for (Entry<Object, Object> entry : System.getProperties().entrySet()) {
            String strKey = entry.getKey().toString();
            if (strKey.startsWith(TRUFFLE_JS_OPTION_PREFIX)) {
                String key = strKey.substring(TRUFFLE_JS_OPTION_PREFIX.length());

                if (key.startsWith(PARSER_OPTION_PREFIX)) {
                    continue;
                }
                System.err.println("WARNING unknown option: " + TRUFFLE_JS_OPTION_PREFIX + key);
            }
        }
    }
}
