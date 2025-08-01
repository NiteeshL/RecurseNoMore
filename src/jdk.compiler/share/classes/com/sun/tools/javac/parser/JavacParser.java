/*
 * Copyright (c) 1999, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.tools.javac.parser;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.lang.model.SourceVersion;

import com.sun.source.tree.CaseTree;
import com.sun.source.tree.MemberReferenceTree.ReferenceMode;
import com.sun.source.tree.ModuleTree.ModuleKind;

import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Source.Feature;
import com.sun.tools.javac.file.PathFileObject;
import com.sun.tools.javac.parser.Tokens.*;
import com.sun.tools.javac.resources.CompilerProperties.Errors;
import com.sun.tools.javac.resources.CompilerProperties.Fragments;
import com.sun.tools.javac.resources.CompilerProperties.LintWarnings;
import com.sun.tools.javac.resources.CompilerProperties.Warnings;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticFlag;
import com.sun.tools.javac.util.JCDiagnostic.Error;
import com.sun.tools.javac.util.JCDiagnostic.Fragment;
import com.sun.tools.javac.util.List;

import static com.sun.tools.javac.parser.Tokens.TokenKind.*;
import static com.sun.tools.javac.parser.Tokens.TokenKind.ASSERT;
import static com.sun.tools.javac.parser.Tokens.TokenKind.CASE;
import static com.sun.tools.javac.parser.Tokens.TokenKind.CATCH;
import static com.sun.tools.javac.parser.Tokens.TokenKind.EQ;
import static com.sun.tools.javac.parser.Tokens.TokenKind.GT;
import static com.sun.tools.javac.parser.Tokens.TokenKind.IMPORT;
import static com.sun.tools.javac.parser.Tokens.TokenKind.LT;
import com.sun.tools.javac.parser.VirtualParser.VirtualScanner;
import static com.sun.tools.javac.tree.JCTree.Tag.*;
import static com.sun.tools.javac.resources.CompilerProperties.Fragments.ImplicitAndExplicitNotAllowed;
import static com.sun.tools.javac.resources.CompilerProperties.Fragments.VarAndExplicitNotAllowed;
import static com.sun.tools.javac.resources.CompilerProperties.Fragments.VarAndImplicitNotAllowed;
import com.sun.tools.javac.util.JCDiagnostic.SimpleDiagnosticPosition;

/**
 * The parser maps a token sequence into an abstract syntax tree.
 * The parser is a hand-written recursive-descent parser that
 * implements the grammar described in the Java Language Specification.
 * For efficiency reasons, an operator precedence scheme is used
 * for parsing binary operation expressions.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class JavacParser implements Parser {

    /** The number of precedence levels of infix operators.
     */
    private static final int infixPrecedenceLevels = 10;

    /** Is the parser instantiated to parse a module-info file ?
     */
    private final boolean parseModuleInfo;

    /** The scanner used for lexical analysis.
     */
    protected Lexer S;

    /** The factory to be used for abstract syntax tree construction.
     */
    protected TreeMaker F;

    /** The log to be used for error diagnostics.
     */
    private Log log;

    /** The Source language setting. */
    private Source source;

    /** The Preview language setting. */
    private Preview preview;

    /** The name table. */
    private Names names;

    /** End position mappings container */
    protected final AbstractEndPosTable endPosTable;

    /** A map associating "other nearby documentation comments"
     *  with the preferred documentation comment for a declaration. */
    protected Map<Comment, List<Comment>> danglingComments = new HashMap<>();
    /** Handler for deferred diagnostics. */
    protected final DeferredLintHandler deferredLintHandler;

    // Because of javac's limited lookahead, some contexts are ambiguous in
    // the presence of type annotations even though they are not ambiguous
    // in the absence of type annotations.  Consider this code:
    //   void m(String [] m) { }
    //   void m(String ... m) { }
    // After parsing "String", javac calls bracketsOpt which immediately
    // returns if the next character is not '['.  Similarly, javac can see
    // if the next token is ... and in that case parse an ellipsis.  But in
    // the presence of type annotations:
    //   void m(String @A [] m) { }
    //   void m(String @A ... m) { }
    // no finite lookahead is enough to determine whether to read array
    // levels or an ellipsis.  Furthermore, if you call bracketsOpt, then
    // bracketsOpt first reads all the leading annotations and only then
    // discovers that it needs to fail.  bracketsOpt needs a way to push
    // back the extra annotations that it read.  (But, bracketsOpt should
    // not *always* be allowed to push back extra annotations that it finds
    // -- in most contexts, any such extra annotation is an error.
    //
    // The following two variables permit type annotations that have
    // already been read to be stored for later use.  Alternate
    // implementations are possible but would cause much larger changes to
    // the parser.

    /** Type annotations that have already been read but have not yet been used. **/
    private List<JCAnnotation> typeAnnotationsPushedBack = List.nil();

    /**
     * If the parser notices extra annotations, then it either immediately
     * issues an error (if this variable is false) or places the extra
     * annotations in variable typeAnnotationsPushedBack (if this variable
     * is true).
     */
    private boolean permitTypeAnnotationsPushBack = false;
    private JCDiagnostic.Error unexpectedTopLevelDefinitionStartError;

    interface ErrorRecoveryAction {
        JCTree doRecover(JavacParser parser);
    }

    enum BasicErrorRecoveryAction implements ErrorRecoveryAction {
        BLOCK_STMT {public JCTree doRecover(JavacParser parser) { return parser.parseStatementAsBlock(); }},
        CATCH_CLAUSE {public JCTree doRecover(JavacParser parser) { return parser.catchClause(); }}
    }

    /** Construct a parser from a given scanner, tree factory and log.
     */
    protected JavacParser(ParserFactory fac,
                          Lexer S,
                          boolean keepDocComments,
                          boolean keepLineMap,
                          boolean keepEndPositions) {
        this(fac, S, keepDocComments, keepLineMap, keepEndPositions, false);

    }
    /** Construct a parser from a given scanner, tree factory and log.
     */
    @SuppressWarnings("this-escape")
    protected JavacParser(ParserFactory fac,
                     Lexer S,
                     boolean keepDocComments,
                     boolean keepLineMap,
                     boolean keepEndPositions,
                     boolean parseModuleInfo) {
        this.S = S;
        nextToken(); // prime the pump
        this.F = fac.F;
        this.log = fac.log;
        this.names = fac.names;
        this.source = fac.source;
        this.preview = fac.preview;
        this.deferredLintHandler = fac.deferredLintHandler;
        this.allowStringFolding = fac.options.getBoolean("allowStringFolding", true);
        this.keepDocComments = keepDocComments;
        this.parseModuleInfo = parseModuleInfo;
        this.docComments = newDocCommentTable(keepDocComments, fac);
        this.keepLineMap = keepLineMap;
        this.errorTree = F.Erroneous();
        this.endPosTable = newEndPosTable(keepEndPositions);
        this.allowYieldStatement = Feature.SWITCH_EXPRESSION.allowedInSource(source);
        this.allowRecords = Feature.RECORDS.allowedInSource(source);
        this.allowSealedTypes = Feature.SEALED_CLASSES.allowedInSource(source);
        updateUnexpectedTopLevelDefinitionStartError(false);
    }

    /** Construct a parser from an existing parser, with minimal overhead.
     */
    @SuppressWarnings("this-escape")
    protected JavacParser(JavacParser parser,
                          Lexer S) {
        this.S = S;
        this.token = parser.token;
        this.F = parser.F;
        this.log = parser.log;
        this.names = parser.names;
        this.source = parser.source;
        this.preview = parser.preview;
        this.deferredLintHandler = parser.deferredLintHandler;
        this.allowStringFolding = parser.allowStringFolding;
        this.keepDocComments = parser.keepDocComments;
        this.parseModuleInfo = false;
        this.docComments = parser.docComments;
        this.errorTree = F.Erroneous();
        this.endPosTable = newEndPosTable(false);
        this.allowYieldStatement = Feature.SWITCH_EXPRESSION.allowedInSource(source);
        this.allowRecords = Feature.RECORDS.allowedInSource(source);
        this.allowSealedTypes = Feature.SEALED_CLASSES.allowedInSource(source);
        updateUnexpectedTopLevelDefinitionStartError(false);
    }

    protected AbstractEndPosTable newEndPosTable(boolean keepEndPositions) {
        return  keepEndPositions
                ? new SimpleEndPosTable()
                : new MinimalEndPosTable();
    }

    protected DocCommentTable newDocCommentTable(boolean keepDocComments, ParserFactory fac) {
        return keepDocComments ? new LazyDocCommentTable(fac) : null;
    }

    /** Switch: should we fold strings?
     */
    boolean allowStringFolding;

    /** Switch: should we keep docComments?
     */
    boolean keepDocComments;

    /** Switch: should we keep line table?
     */
    boolean keepLineMap;

    /** Switch: is "this" allowed as an identifier?
     * This is needed to parse receiver types.
     */
    boolean allowThisIdent;

    /** Switch: is yield statement allowed in this source level?
     */
    boolean allowYieldStatement;

    /** Switch: are records allowed in this source level?
     */
    boolean allowRecords;

    /** Switch: are sealed types allowed in this source level?
     */
    boolean allowSealedTypes;

    /** The type of the method receiver, as specified by a first "this" parameter.
     */
    JCVariableDecl receiverParam;

    /** When terms are parsed, the mode determines which is expected:
     *     mode = EXPR        : an expression
     *     mode = TYPE        : a type
     *     mode = NOPARAMS    : no parameters allowed for type
     *     mode = TYPEARG     : type argument
     *     mode |= NOLAMBDA   : lambdas are not allowed
     */
    protected static final int EXPR          = 1 << 0;
    protected static final int TYPE          = 1 << 1;
    protected static final int NOPARAMS      = 1 << 2;
    protected static final int TYPEARG       = 1 << 3;
    protected static final int DIAMOND       = 1 << 4;
    protected static final int NOLAMBDA      = 1 << 5;

    protected void setMode(int mode) {
        this.mode = mode;
    }

    protected void setLastMode(int mode) {
        lastmode = mode;
    }

    protected boolean isMode(int mode) {
        return (this.mode & mode) != 0;
    }

    protected boolean wasTypeMode() {
        return (lastmode & TYPE) != 0;
    }

    protected void selectExprMode() {
        setMode((mode & NOLAMBDA) | EXPR);
    }

    protected void selectTypeMode() {
        setMode((mode & NOLAMBDA) | TYPE);
    }

    /** The current mode.
     */
    protected int mode = 0;

    /** The mode of the term that was parsed last.
     */
    protected int lastmode = 0;

    /* ---------- token management -------------- */

    protected Token token;

    public Token token() {
        return token;
    }

    public void nextToken() {
        S.nextToken();
        token = S.token();
    }

    protected boolean peekToken(Predicate<TokenKind> tk) {
        return peekToken(0, tk);
    }

    protected boolean peekToken(int lookahead, Predicate<TokenKind> tk) {
        return tk.test(S.token(lookahead + 1).kind);
    }

    protected boolean peekToken(Predicate<TokenKind> tk1, Predicate<TokenKind> tk2) {
        return peekToken(0, tk1, tk2);
    }

    protected boolean peekToken(int lookahead, Predicate<TokenKind> tk1, Predicate<TokenKind> tk2) {
        return tk1.test(S.token(lookahead + 1).kind) &&
                tk2.test(S.token(lookahead + 2).kind);
    }

    protected boolean peekToken(Predicate<TokenKind> tk1, Predicate<TokenKind> tk2, Predicate<TokenKind> tk3) {
        return peekToken(0, tk1, tk2, tk3);
    }

    protected boolean peekToken(int lookahead, Predicate<TokenKind> tk1, Predicate<TokenKind> tk2, Predicate<TokenKind> tk3) {
        return tk1.test(S.token(lookahead + 1).kind) &&
                tk2.test(S.token(lookahead + 2).kind) &&
                tk3.test(S.token(lookahead + 3).kind);
    }

    @SuppressWarnings("unchecked")
    protected boolean peekToken(Predicate<TokenKind>... kinds) {
        return peekToken(0, kinds);
    }

    @SuppressWarnings("unchecked")
    protected boolean peekToken(int lookahead, Predicate<TokenKind>... kinds) {
        for (Predicate<TokenKind> kind : kinds) {
            if (!kind.test(S.token(++lookahead).kind)) {
                return false;
            }
        }
        return true;
    }

    /* ---------- error recovery -------------- */

    private JCErroneous errorTree;

    /** Skip forward until a suitable stop token is found.
     */
    protected void skip(boolean stopAtImport, boolean stopAtMemberDecl, boolean stopAtIdentifier, boolean stopAtStatement) {
         while (true) {
             switch (token.kind) {
                case SEMI:
                    nextToken();
                    return;
                case PUBLIC:
                case FINAL:
                case ABSTRACT:
                case MONKEYS_AT:
                case EOF:
                case CLASS:
                case INTERFACE:
                case ENUM:
                    return;
                case IMPORT:
                    if (stopAtImport)
                        return;
                    break;
                case LBRACE:
                case RBRACE:
                case PRIVATE:
                case PROTECTED:
                case STATIC:
                case TRANSIENT:
                case NATIVE:
                case VOLATILE:
                case SYNCHRONIZED:
                case STRICTFP:
                case LT:
                case BYTE:
                case SHORT:
                case CHAR:
                case INT:
                case LONG:
                case FLOAT:
                case DOUBLE:
                case BOOLEAN:
                case VOID:
                    if (stopAtMemberDecl)
                        return;
                    break;
                case UNDERSCORE:
                case IDENTIFIER:
                   if (stopAtIdentifier)
                        return;
                    break;
                case CASE:
                case DEFAULT:
                case IF:
                case FOR:
                case WHILE:
                case DO:
                case TRY:
                case SWITCH:
                case RETURN:
                case THROW:
                case BREAK:
                case CONTINUE:
                case ELSE:
                case FINALLY:
                case CATCH:
                case THIS:
                case SUPER:
                case NEW:
                    if (stopAtStatement)
                        return;
                    break;
                case ASSERT:
                    if (stopAtStatement)
                        return;
                    break;
            }
            nextToken();
        }
    }

    protected JCErroneous syntaxError(int pos, Error errorKey) {
        return syntaxError(pos, List.nil(), errorKey);
    }

    protected JCErroneous syntaxError(int pos, List<? extends JCTree> errs, Error errorKey) {
        return syntaxError(pos, errs, errorKey, false);
    }

    private JCErroneous syntaxError(int pos, List<? extends JCTree> errs, Error errorKey, boolean noEofError) {
        setErrorEndPos(pos);
        JCErroneous err = F.at(pos).Erroneous(errs);
        reportSyntaxError(err, errorKey, noEofError);
        if (errs != null) {
            JCTree last = errs.last();
            if (last != null)
                storeEnd(last, pos);
        }
        return toP(err);
    }

    private static final int RECOVERY_THRESHOLD = 50;
    private int errorPos = Position.NOPOS;
    private int count = 0;

    /**
     * Report a syntax using the given the position parameter and arguments,
     * unless one was already reported at the same position.
     */
    protected void reportSyntaxError(int pos, Error errorKey) {
        JCDiagnostic.DiagnosticPosition diag = new JCDiagnostic.SimpleDiagnosticPosition(pos);
        reportSyntaxError(diag, errorKey);
    }

    /**
     * Report a syntax error using the given DiagnosticPosition object and
     * arguments, unless one was already reported at the same position.
     */
    protected void reportSyntaxError(JCDiagnostic.DiagnosticPosition diagPos, Error errorKey) {
        reportSyntaxError(diagPos, errorKey, false);
    }

    private void reportSyntaxError(JCDiagnostic.DiagnosticPosition diagPos, Error errorKey, boolean noEofError) {
        int pos = diagPos.getPreferredPosition();
        if (pos > S.errPos() || pos == Position.NOPOS) {
            if (token.kind == EOF && !noEofError) {
                log.error(DiagnosticFlag.SYNTAX, diagPos, Errors.PrematureEof);
            } else {
                log.error(DiagnosticFlag.SYNTAX, diagPos, errorKey);
            }
        }
        S.errPos(pos);
        if (token.pos == errorPos && token.kind != EOF) {
            //check for a possible infinite loop in parsing:
            Assert.check(count++ < RECOVERY_THRESHOLD);
        } else {
            count = 0;
            errorPos = token.pos;
        }
    }

    /** If next input token matches given token, skip it, otherwise report
     *  an error.
     */
    public void accept(TokenKind tk) {
        accept(tk, Errors::Expected);
    }

    /** If next input token matches given token, skip it, otherwise report
     *  an error.
     */
    public void accept(TokenKind tk, Function<TokenKind, Error> errorProvider) {
        if (token.kind == tk) {
            nextToken();
        } else {
            setErrorEndPos(token.pos);
            reportSyntaxError(S.prevToken().endPos, errorProvider.apply(tk));
        }
    }

    /** Report an illegal start of expression/type error at given position.
     */
    JCExpression illegal(int pos) {
        setErrorEndPos(pos);
        if (isMode(EXPR))
            return syntaxError(pos, Errors.IllegalStartOfExpr);
        else
            return syntaxError(pos, Errors.IllegalStartOfType);

    }

    /** Report an illegal start of expression/type error at current position.
     */
    JCExpression illegal() {
        return illegal(token.pos);
    }

    /** Diagnose a modifier flag from the set, if any. */
    protected void checkNoMods(long mods) {
        checkNoMods(token.pos, mods);
    }

    protected void checkNoMods(int pos, long mods) {
        if (mods != 0) {
            long lowestMod = mods & -mods;
            log.error(DiagnosticFlag.SYNTAX, pos, Errors.ModNotAllowedHere(Flags.asFlagSet(lowestMod)));
        }
    }

/* ---------- doc comments --------- */

    /** A table to store all documentation comments
     *  indexed by the tree nodes they refer to.
     *  defined only if option flag keepDocComment is set.
     */
    private final DocCommentTable docComments;

    /** Record nearby documentation comments against the
     *  primary documentation comment for a declaration.
     *
     *  Dangling documentation comments are handled as follows.
     *  1. {@code Scanner} adds all doc comments to a queue of
     *     recent doc comments. The queue is flushed whenever
     *     it is known that the recent doc comments should be
     *     ignored and should not cause any warnings.
     *  2. The primary documentation comment is the one obtained
     *     from the first token of any declaration.
     *     (using {@code token.getDocComment()}.
     *  3. At the end of the "signature" of the declaration
     *     (that is, before any initialization or body for the
     *     declaration) any other "recent" comments are saved
     *     in a map using the primary comment as a key,
     *     using this method, {@code saveDanglingComments}.
     *  4. When the tree node for the declaration is finally
     *     available, and the primary comment, if any,
     *     is "attached", (in {@link #attach}) any related
     *     dangling comments are also attached to the tree node
     *     by registering them using the {@link #deferredLintHandler}.
     *  5. (Later) Warnings may be generated for the dangling
     *     comments, subject to the {@code -Xlint} and
     *     {@code @SuppressWarnings}.
     *
     *  @param dc the primary documentation comment
     */
    private void saveDanglingDocComments(Comment dc) {
        var recentComments = S.getDocComments();

        switch (recentComments.size()) {
            case 0:
                // no recent comments
                return;

            case 1:
                if (recentComments.peek() == dc) {
                    // no other recent comments
                    recentComments.remove();
                    return;
                }
        }

        var lb = new ListBuffer<Comment>();
        while (!recentComments.isEmpty()) {
            var c = recentComments.remove();
            if (c != dc) {
                lb.add(c);
            }
        }
        danglingComments.put(dc, lb.toList());
    }

    /** Make an entry into docComments hashtable,
     *  provided flag keepDocComments is set and given doc comment is non-null.
     *  If there are any related "dangling comments", register
     *  diagnostics to be handled later, when @SuppressWarnings
     *  can be taken into account.
     *
     *  @param tree   The tree to be used as index in the hashtable
     *  @param dc     The doc comment to associate with the tree, or null.
     *  @return {@code tree}
     */
    protected <T extends JCTree> T attach(T tree, Comment dc) {
        if (keepDocComments && dc != null) {
            docComments.putComment(tree, dc);
        }
        reportDanglingComments(tree, dc);
        return tree;
    }

    /** Reports all dangling comments associated with the
     *  primary comment for a declaration against the position
     *  of the tree node for a declaration.
     *
     * @param tree the tree node for the declaration
     * @param dc the primary comment for the declaration
     */
    void reportDanglingComments(JCTree tree, Comment dc) {
        var list = danglingComments.remove(dc);
        if (list != null) {
            deferredLintHandler.push(tree);
            try {
                list.forEach(this::reportDanglingDocComment);
            } finally {
                deferredLintHandler.pop();
            }
        }
    }

    /**
     * Reports an individual dangling comment using the {@link #deferredLintHandler}.
     * The comment may or not may generate an actual diagnostic, depending on
     * the settings for {@code -Xlint} and/or {@code @SuppressWarnings}.
     *
     * @param c the comment
     */
    void reportDanglingDocComment(Comment c) {
        var pos = c.getPos();
        if (pos != null) {
            deferredLintHandler.report(lint -> {
                if (lint.isEnabled(Lint.LintCategory.DANGLING_DOC_COMMENTS) &&
                        !shebang(c, pos)) {
                    log.warning(
                            pos, LintWarnings.DanglingDocComment);
                }
            });
        }
    }

    /** Returns true for a comment that acts similarly to shebang in UNIX */
    private boolean shebang(Comment c, JCDiagnostic.DiagnosticPosition pos) {
        var src = log.currentSource();
        return c.getStyle() == Comment.CommentStyle.JAVADOC_LINE &&
                c.getPos().getStartPosition() == 0 &&
                src.getLineNumber(pos.getEndPosition(src.getEndPosTable())) == 1;
    }

    /**
     * Ignores any recent documentation comments found by the scanner,
     * such as those that cannot be associated with a nearby declaration.
     */
    private void ignoreDanglingComments() {
        S.getDocComments().clear();
    }

/* -------- source positions ------- */

    protected void setErrorEndPos(int errPos) {
        endPosTable.setErrorEndPos(errPos);
    }

    /**
     * Store ending position for a tree, the value of which is the greater of
     * last error position in {@link #endPosTable} and the given ending position.
     * @param tree tree node
     * @param endpos the ending position to associate with {@code tree}
     * @return {@code tree}
     */
    protected <T extends JCTree> T storeEnd(T tree, int endpos) {
        return endPosTable.storeEnd(tree, endpos);
    }

    /**
     * Store current token's ending position for a tree, the value of which
     * will be the greater of last error position in {@link #endPosTable}
     * and the ending position of the current token.
     * @param tree tree node
     */
    protected <T extends JCTree> T to(T tree) {
        return storeEnd(tree, token.endPos);
    }

    /**
     * Store current token's ending position for a tree, the value of which
     * will be the greater of last error position in {@link #endPosTable}
     * and the ending position of the previous token.
     * @param tree tree node
     */
    protected <T extends JCTree> T toP(T tree) {
        return storeEnd(tree, S.prevToken().endPos);
    }

    /** Get the start position for a tree node.  The start position is
     * defined to be the position of the first character of the first
     * token of the node's source text.
     * @param tree  The tree node
     */
    public int getStartPos(JCTree tree) {
        return TreeInfo.getStartPos(tree);
    }

    /**
     * Get the end position for a tree node.  The end position is
     * defined to be the position of the last character of the last
     * token of the node's source text.  Returns Position.NOPOS if end
     * positions are not generated or the position is otherwise not
     * found.
     * @param tree  The tree node
     */
    public int getEndPos(JCTree tree) {
        return endPosTable.getEndPos(tree);
    }



/* ---------- parsing -------------- */

    /**
     * Ident = IDENTIFIER
     */
    public Name ident() {
        return ident(false);
    }

    protected Name ident(boolean allowClass) {
        return ident(allowClass, false);
    }

    public Name identOrUnderscore() {
        return ident(false, true);
    }

    protected Name ident(boolean allowClass, boolean asVariable) {
        if (token.kind == IDENTIFIER) {
            Name name = token.name();
            nextToken();
            return name;
        } else if (token.kind == ASSERT) {
            log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.AssertAsIdentifier);
            nextToken();
            return names.error;
        } else if (token.kind == ENUM) {
            log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.EnumAsIdentifier);
            nextToken();
            return names.error;
        } else if (token.kind == THIS) {
            if (allowThisIdent) {
                Name name = token.name();
                nextToken();
                return name;
            } else {
                log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.ThisAsIdentifier);
                nextToken();
                return names.error;
            }
        } else if (token.kind == UNDERSCORE) {
            if (Feature.UNDERSCORE_IDENTIFIER.allowedInSource(source)) {
                log.warning(token.pos, Warnings.UnderscoreAsIdentifier);
            } else if (asVariable) {
                checkSourceLevel(Feature.UNNAMED_VARIABLES);
                if (peekToken(LBRACKET)) {
                    log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.UseOfUnderscoreNotAllowedWithBrackets);
                }
            } else {
                if (Feature.UNNAMED_VARIABLES.allowedInSource(source)) {
                    log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.UseOfUnderscoreNotAllowedNonVariable);
                } else {
                    log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.UnderscoreAsIdentifier);
                }
            }
            Name name = token.name();
            nextToken();
            return name;
        } else {
            accept(IDENTIFIER);
            if (allowClass && token.kind == CLASS) {
                nextToken();
                return names._class;
            }
            return names.error;
        }
    }

    /**
     * Qualident = Ident { DOT [Annotations] Ident }
     */
    public JCExpression qualident(boolean allowAnnos) {
        JCExpression t = toP(F.at(token.pos).Ident(ident()));
        while (token.kind == DOT) {
            int pos = token.pos;
            nextToken();
            List<JCAnnotation> tyannos = null;
            if (allowAnnos) {
                tyannos = typeAnnotationsOpt();
            }
            t = toP(F.at(pos).Select(t, ident()));
            if (tyannos != null && tyannos.nonEmpty()) {
                t = toP(F.at(tyannos.head.pos).AnnotatedType(tyannos, t));
            }
        }
        return t;
    }

    JCExpression literal(Name prefix) {
        return literal(prefix, token.pos);
    }

    /**
     * Literal =
     *     INTLITERAL
     *   | LONGLITERAL
     *   | FLOATLITERAL
     *   | DOUBLELITERAL
     *   | CHARLITERAL
     *   | STRINGLITERAL
     *   | TRUE
     *   | FALSE
     *   | NULL
     */
    JCExpression literal(Name prefix, int pos) {
        JCExpression t = errorTree;
        switch (token.kind) {
        case INTLITERAL:
            try {
                t = F.at(pos).Literal(
                    TypeTag.INT,
                    Convert.string2int(strval(prefix), token.radix()));
            } catch (NumberFormatException ex) {
                reportIntegralLiteralError(prefix, pos);
            }
            break;
        case LONGLITERAL:
            try {
                t = F.at(pos).Literal(
                    TypeTag.LONG,
                    Long.valueOf(Convert.string2long(strval(prefix), token.radix())));
            } catch (NumberFormatException ex) {
                reportIntegralLiteralError(prefix, pos);
            }
            break;
        case FLOATLITERAL: {
            String proper = token.radix() == 16 ?
                    ("0x"+ token.stringVal()) :
                    token.stringVal();
            Float n;
            try {
                n = Float.valueOf(proper);
            } catch (NumberFormatException ex) {
                // error already reported in scanner
                n = Float.NaN;
            }
            if (n.floatValue() == 0.0f && !isZero(proper))
                log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.FpNumberTooSmall);
            else if (n.floatValue() == Float.POSITIVE_INFINITY)
                log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.FpNumberTooLarge);
            else
                t = F.at(pos).Literal(TypeTag.FLOAT, n);
            break;
        }
        case DOUBLELITERAL: {
            String proper = token.radix() == 16 ?
                    ("0x"+ token.stringVal()) :
                    token.stringVal();
            Double n;
            try {
                n = Double.valueOf(proper);
            } catch (NumberFormatException ex) {
                // error already reported in scanner
                n = Double.NaN;
            }
            if (n.doubleValue() == 0.0d && !isZero(proper))
                log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.FpNumberTooSmall);
            else if (n.doubleValue() == Double.POSITIVE_INFINITY)
                log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.FpNumberTooLarge);
            else
                t = F.at(pos).Literal(TypeTag.DOUBLE, n);
            break;
        }
        case CHARLITERAL:
            t = F.at(pos).Literal(
                TypeTag.CHAR,
                token.stringVal().charAt(0) + 0);
            break;
        case STRINGLITERAL:
            t = F.at(pos).Literal(
                TypeTag.CLASS,
                token.stringVal());
            break;
        case TRUE: case FALSE:
            t = F.at(pos).Literal(
                TypeTag.BOOLEAN,
                (token.kind == TRUE ? 1 : 0));
            break;
        case NULL:
            t = F.at(pos).Literal(
                TypeTag.BOT,
                null);
            break;
        default:
            Assert.error();
        }
        if (t == errorTree)
            t = F.at(pos).Erroneous();
        storeEnd(t, token.endPos);
        nextToken();
        return t;
    }
    //where
        boolean isZero(String s) {
            char[] cs = s.toCharArray();
            int base = ((cs.length > 1 && Character.toLowerCase(cs[1]) == 'x') ? 16 : 10);
            int i = ((base==16) ? 2 : 0);
            while (i < cs.length && (cs[i] == '0' || cs[i] == '.')) i++;
            return !(i < cs.length && (Character.digit(cs[i], base) > 0));
        }

        String strval(Name prefix) {
            String s = token.stringVal();
            return prefix.isEmpty() ? s : prefix + s;
        }
        void reportIntegralLiteralError(Name prefix, int pos) {
            int radix = token.radix();
            if (radix == 2 || radix == 8) {
                //attempt to produce more user-friendly error message for
                //binary and octal literals with wrong digits:
                String value = strval(prefix);
                char[] cs = value.toCharArray();
                for (int i = 0; i < cs.length; i++) {
                    char c = cs[i];
                    int d = Character.digit(c, radix);
                    if (d == (-1)) {
                        Error err = radix == 2 ? Errors.IllegalDigitInBinaryLiteral
                                               : Errors.IllegalDigitInOctalLiteral;
                        log.error(DiagnosticFlag.SYNTAX,
                                  token.pos + i,
                                  err);
                        return ;
                    }
                }
            }
            log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.IntNumberTooLarge(strval(prefix)));
        }

    /** terms can be either expressions or types.
     */
    public JCExpression parseExpression() {
        return term(EXPR);
    }

    /** parses patterns.
     */
    public JCPattern parsePattern(int pos, JCModifiers mods, JCExpression parsedType,
                                  boolean allowVar, boolean checkGuard) {
        JCPattern pattern;
        mods = mods != null ? mods : optFinal(0);
        JCExpression e;
        if (token.kind == UNDERSCORE && parsedType == null) {
            nextToken();
            checkSourceLevel(Feature.UNNAMED_VARIABLES);
            pattern = toP(F.at(token.pos).AnyPattern());
        }
        else {
            if (parsedType == null) {
                boolean var = token.kind == IDENTIFIER && token.name() == names.var;
                e = unannotatedType(allowVar, TYPE | NOLAMBDA);
                if (var) {
                    e = null;
                }
            } else {
                e = parsedType;
            }
            if (token.kind == LPAREN) {
                //deconstruction pattern:
                checkSourceLevel(Feature.RECORD_PATTERNS);
                ListBuffer<JCPattern> nested = new ListBuffer<>();
                if (!peekToken(RPAREN)) {
                    do {
                        nextToken();
                        JCPattern nestedPattern = parsePattern(token.pos, null, null, true, false);
                        nested.append(nestedPattern);
                    } while (token.kind == COMMA);
                } else {
                    nextToken();
                }
                accept(RPAREN);
                pattern = toP(F.at(pos).RecordPattern(e, nested.toList()));
                if (mods.annotations.nonEmpty()) {
                    log.error(mods.annotations.head.pos(), Errors.RecordPatternsAnnotationsNotAllowed);
                }
                checkNoMods(pos, mods.flags & Flags.FINAL);
                new TreeScanner() {
                    @Override
                    public void visitAnnotatedType(JCAnnotatedType tree) {
                        log.error(tree.pos(), Errors.RecordPatternsAnnotationsNotAllowed);
                    }
                }.scan(e);
            } else {
                //type test pattern:
                int varPos = token.pos;
                Name name = identOrUnderscore();
                if (Feature.UNNAMED_VARIABLES.allowedInSource(source) && name == names.underscore) {
                    name = names.empty;
                }
                JCVariableDecl var = toP(F.at(varPos).VarDef(mods, name, e, null));
                if (e == null) {
                    var.startPos = pos;
                    if (var.name == names.underscore && !allowVar) {
                        log.error(DiagnosticFlag.SYNTAX, varPos, Errors.UseOfUnderscoreNotAllowed);
                    }
                }
                pattern = toP(F.at(pos).BindingPattern(var));
            }
        }
        return pattern;
    }

    /**
     * parses (optional) type annotations followed by a type. If the
     * annotations are present before the type and are not consumed during array
     * parsing, this method returns a {@link JCAnnotatedType} consisting of
     * these annotations and the underlying type. Otherwise, it returns the
     * underlying type.
     *
     * <p>
     *
     * Note that this method sets {@code mode} to {@code TYPE} first, before
     * parsing annotations.
     */
    public JCExpression parseType() {
        return parseType(false);
    }

    public JCExpression parseType(boolean allowVar) {
        List<JCAnnotation> annotations = typeAnnotationsOpt();
        return parseType(allowVar, annotations);
    }

    public JCExpression parseType(boolean allowVar, List<JCAnnotation> annotations) {
        JCExpression result = unannotatedType(allowVar);

        if (annotations.nonEmpty()) {
            result = insertAnnotationsToMostInner(result, annotations, false);
        }

        return result;
    }

    protected JCExpression parseIntersectionType(int pos, JCExpression firstType) {
        JCExpression t = firstType;
        int pos1 = pos;
        List<JCExpression> targets = List.of(t);
        while (token.kind == AMP) {
            accept(AMP);
            targets = targets.prepend(parseType());
        }
        if (targets.length() > 1) {
            t = toP(F.at(pos1).TypeIntersection(targets.reverse()));
        }
        return t;
    }

    public JCExpression unannotatedType(boolean allowVar) {
        return unannotatedType(allowVar, TYPE);
    }

    public JCExpression unannotatedType(boolean allowVar, int newmode) {
        JCExpression result = term(newmode);
        Name restrictedTypeName = restrictedTypeName(result, !allowVar);

        if (restrictedTypeName != null && (!allowVar || restrictedTypeName != names.var)) {
            syntaxError(result.pos, Errors.RestrictedTypeNotAllowedHere(restrictedTypeName));
        }

        return result;
    }



    protected JCExpression term(int newmode) {
        int prevmode = mode;
        setMode(newmode);
        JCExpression t = term();
        setLastMode(mode);
        setMode(prevmode);
        return t;
    }

    /**
     *  {@literal
     *  Expression = Expression1 [ExpressionRest]
     *  ExpressionRest = [AssignmentOperator Expression1]
     *  AssignmentOperator = "=" | "+=" | "-=" | "*=" | "/=" |
     *                       "&=" | "|=" | "^=" |
     *                       "%=" | "<<=" | ">>=" | ">>>="
     *  Type = Type1
     *  TypeNoParams = TypeNoParams1
     *  StatementExpression = Expression
     *  ConstantExpression = Expression
     *  }
     */
    JCExpression term() {
        JCExpression t = term1();
        if (isMode(EXPR) &&
            (token.kind == EQ || PLUSEQ.compareTo(token.kind) <= 0 && token.kind.compareTo(GTGTGTEQ) <= 0))
            return termRest(t);
        else
            return t;
    }

    JCExpression termRest(JCExpression t) {
        switch (token.kind) {
        case EQ: {
            int pos = token.pos;
            nextToken();
            selectExprMode();
            JCExpression t1 = term();
            return toP(F.at(pos).Assign(t, t1));
        }
        case PLUSEQ:
        case SUBEQ:
        case STAREQ:
        case SLASHEQ:
        case PERCENTEQ:
        case AMPEQ:
        case BAREQ:
        case CARETEQ:
        case LTLTEQ:
        case GTGTEQ:
        case GTGTGTEQ:
            int pos = token.pos;
            TokenKind tk = token.kind;
            nextToken();
            selectExprMode();
            JCExpression t1 = term();
            return F.at(pos).Assignop(optag(tk), t, t1);
        default:
            return t;
        }
    }

    /** Expression1   = Expression2 [Expression1Rest]
     *  Type1         = Type2
     *  TypeNoParams1 = TypeNoParams2
     */
    JCExpression term1() {
        JCExpression t = term2();
        if (isMode(EXPR) && token.kind == QUES) {
            selectExprMode();
            return term1Rest(t);
        } else {
            return t;
        }
    }

    /** Expression1Rest = ["?" Expression ":" Expression1]
     */
    JCExpression term1Rest(JCExpression t) {
        if (token.kind == QUES) {
            int pos = token.pos;
            nextToken();
            JCExpression t1 = term();
            accept(COLON);
            JCExpression t2 = term1();
            return F.at(pos).Conditional(t, t1, t2);
        } else {
            return t;
        }
    }

    /** Expression2   = Expression3 [Expression2Rest]
     *  Type2         = Type3
     *  TypeNoParams2 = TypeNoParams3
     */
    JCExpression term2() {
        JCExpression t = term3();
        if (isMode(EXPR) && prec(token.kind) >= TreeInfo.orPrec) {
            selectExprMode();
            return term2Rest(t, TreeInfo.orPrec);
        } else {
            return t;
        }
    }

    /*  Expression2Rest = {infixop Expression3}
     *                  | Expression3 instanceof Type
     *                  | Expression3 instanceof Pattern
     *  infixop         = "||"
     *                  | "&&"
     *                  | "|"
     *                  | "^"
     *                  | "&"
     *                  | "==" | "!="
     *                  | "<" | ">" | "<=" | ">="
     *                  | "<<" | ">>" | ">>>"
     *                  | "+" | "-"
     *                  | "*" | "/" | "%"
     */
    JCExpression term2Rest(JCExpression t, int minprec) {
        JCExpression[] odStack = newOdStack();
        Token[] opStack = newOpStack();

        // optimization, was odStack = new Tree[...]; opStack = new Tree[...];
        int top = 0;
        odStack[0] = t;
        int startPos = token.pos;
        Token topOp = Tokens.DUMMY;
        while (prec(token.kind) >= minprec) {
            opStack[top] = topOp;

            if (token.kind == INSTANCEOF) {
                int pos = token.pos;
                nextToken();
                JCTree pattern;
                if (token.kind == LPAREN) {
                    checkSourceLevel(token.pos, Feature.PATTERN_SWITCH);
                    pattern = parsePattern(token.pos, null, null, false, false);
                } else {
                    int patternPos = token.pos;
                    JCModifiers mods = optFinal(0);
                    int typePos = token.pos;
                    JCExpression type = unannotatedType(false);
                    if (token.kind == IDENTIFIER) {
                        checkSourceLevel(token.pos, Feature.PATTERN_MATCHING_IN_INSTANCEOF);
                        pattern = parsePattern(patternPos, mods, type, false, false);
                    } else if (token.kind == LPAREN) {
                        pattern = parsePattern(patternPos, mods, type, false, false);
                    } else if (token.kind == UNDERSCORE) {
                        checkSourceLevel(token.pos, Feature.UNNAMED_VARIABLES);
                        pattern = parsePattern(patternPos, mods, type, false, false);
                    } else {
                        checkNoMods(typePos, mods.flags & ~Flags.DEPRECATED);
                        if (mods.annotations.nonEmpty()) {
                            List<JCAnnotation> typeAnnos =
                                    mods.annotations
                                        .map(decl -> {
                                            JCAnnotation typeAnno = F.at(decl.pos)
                                                                     .TypeAnnotation(decl.annotationType,
                                                                                      decl.args);
                                            endPosTable.replaceTree(decl, typeAnno);
                                            return typeAnno;
                                        });
                            type = insertAnnotationsToMostInner(type, typeAnnos, false);
                        }
                        pattern = type;
                    }
                }
                odStack[top] = F.at(pos).TypeTest(odStack[top], pattern);
            } else {
                topOp = token;
                nextToken();
                top++;
                odStack[top] = term3();
            }
            while (top > 0 && prec(topOp.kind) >= prec(token.kind)) {
                odStack[top - 1] = F.at(topOp.pos).Binary(optag(topOp.kind), odStack[top - 1], odStack[top]);
                top--;
                topOp = opStack[top];
            }
        }
        Assert.check(top == 0);
        t = odStack[0];

        if (t.hasTag(JCTree.Tag.PLUS)) {
            t = foldStrings(t);
        }

        odStackSupply.add(odStack);
        opStackSupply.add(opStack);
        return t;
    }
    //where
        /** If tree is a concatenation of string literals, replace it
         *  by a single literal representing the concatenated string.
         */
        protected JCExpression foldStrings(JCExpression tree) {
            if (!allowStringFolding)
                return tree;
            ListBuffer<JCExpression> opStack = new ListBuffer<>();
            ListBuffer<JCLiteral> litBuf = new ListBuffer<>();
            boolean needsFolding = false;
            JCExpression curr = tree;
            while (true) {
                if (curr.hasTag(JCTree.Tag.PLUS)) {
                    JCBinary op = (JCBinary)curr;
                    needsFolding |= foldIfNeeded(op.rhs, litBuf, opStack, false);
                    curr = op.lhs;
                } else {
                    needsFolding |= foldIfNeeded(curr, litBuf, opStack, true);
                    break; //last one!
                }
            }
            if (needsFolding) {
                List<JCExpression> ops = opStack.toList();
                JCExpression res = ops.head;
                for (JCExpression op : ops.tail) {
                    res = F.at(op.getStartPosition()).Binary(optag(TokenKind.PLUS), res, op);
                    storeEnd(res, getEndPos(op));
                }
                return res;
            } else {
                return tree;
            }
        }

        private boolean foldIfNeeded(JCExpression tree, ListBuffer<JCLiteral> litBuf,
                                                ListBuffer<JCExpression> opStack, boolean last) {
            JCLiteral str = stringLiteral(tree);
            if (str != null) {
                litBuf.prepend(str);
                return last && merge(litBuf, opStack);
            } else {
                boolean res = merge(litBuf, opStack);
                litBuf.clear();
                opStack.prepend(tree);
                return res;
            }
        }

        boolean merge(ListBuffer<JCLiteral> litBuf, ListBuffer<JCExpression> opStack) {
            if (litBuf.isEmpty()) {
                return false;
            } else if (litBuf.size() == 1) {
                opStack.prepend(litBuf.first());
                return false;
            } else {
                JCExpression t = F.at(litBuf.first().getStartPosition()).Literal(TypeTag.CLASS,
                        litBuf.stream().map(lit -> (String)lit.getValue()).collect(Collectors.joining()));
                storeEnd(t, litBuf.last().getEndPosition(endPosTable));
                opStack.prepend(t);
                return true;
            }
        }

        private JCLiteral stringLiteral(JCTree tree) {
            if (tree.hasTag(LITERAL)) {
                JCLiteral lit = (JCLiteral)tree;
                if (lit.typetag == TypeTag.CLASS) {
                    return lit;
                }
            }
            return null;
        }


        /** optimization: To save allocating a new operand/operator stack
         *  for every binary operation, we use supplys.
         */
        ArrayList<JCExpression[]> odStackSupply = new ArrayList<>();
        ArrayList<Token[]> opStackSupply = new ArrayList<>();

        private JCExpression[] newOdStack() {
            if (odStackSupply.isEmpty())
                return new JCExpression[infixPrecedenceLevels + 1];
            return odStackSupply.remove(odStackSupply.size() - 1);
        }

        private Token[] newOpStack() {
            if (opStackSupply.isEmpty())
                return new Token[infixPrecedenceLevels + 1];
            return opStackSupply.remove(opStackSupply.size() - 1);
        }

    /**
     *  Expression3    = PrefixOp Expression3
     *                 | "(" Expr | TypeNoParams ")" Expression3
     *                 | Primary {Selector} {PostfixOp}
     *
     *  {@literal
     *  Primary        = "(" Expression ")"
     *                 | Literal
     *                 | [TypeArguments] THIS [Arguments]
     *                 | [TypeArguments] SUPER SuperSuffix
     *                 | NEW [TypeArguments] Creator
     *                 | "(" Arguments ")" "->" ( Expression | Block )
     *                 | Ident "->" ( Expression | Block )
     *                 | [Annotations] Ident { "." [Annotations] Ident }
     *                 | Expression3 MemberReferenceSuffix
     *                   [ [Annotations] "[" ( "]" BracketsOpt "." CLASS | Expression "]" )
     *                   | Arguments
     *                   | "." ( CLASS | THIS | [TypeArguments] SUPER Arguments | NEW [TypeArguments] InnerCreator )
     *                   ]
     *                 | BasicType BracketsOpt "." CLASS
     *  }
     *
     *  PrefixOp       = "++" | "--" | "!" | "~" | "+" | "-"
     *  PostfixOp      = "++" | "--"
     *  Type3          = Ident { "." Ident } [TypeArguments] {TypeSelector} BracketsOpt
     *                 | BasicType
     *  TypeNoParams3  = Ident { "." Ident } BracketsOpt
     *  Selector       = "." [TypeArguments] Ident [Arguments]
     *                 | "." THIS
     *                 | "." [TypeArguments] SUPER SuperSuffix
     *                 | "." NEW [TypeArguments] InnerCreator
     *                 | "[" Expression "]"
     *  TypeSelector   = "." Ident [TypeArguments]
     *  SuperSuffix    = Arguments | "." Ident [Arguments]
     */
    protected JCExpression term3() {
        int pos = token.pos;
        JCExpression t;
        List<JCExpression> typeArgs = typeArgumentsOpt(EXPR);
        switch (token.kind) {
        case QUES:
            if (isMode(TYPE) && isMode(TYPEARG) && !isMode(NOPARAMS)) {
                selectTypeMode();
                return typeArgument();
            } else
                return illegal();
        case PLUSPLUS: case SUBSUB: case BANG: case TILDE: case PLUS: case SUB:
            if (typeArgs == null && isMode(EXPR)) {
                TokenKind tk = token.kind;
                nextToken();
                selectExprMode();
                if (tk == SUB &&
                    (token.kind == INTLITERAL || token.kind == LONGLITERAL) &&
                    token.radix() == 10) {
                    selectExprMode();
                    t = literal(names.hyphen, pos);
                } else {
                    t = term3();
                    return F.at(pos).Unary(unoptag(tk), t);
                }
            } else return illegal();
            break;
        case LPAREN:
            if (typeArgs == null && isMode(EXPR)) {
                ParensResult pres = analyzeParens();
                switch (pres) {
                    case CAST:
                       accept(LPAREN);
                       selectTypeMode();
                       t = parseIntersectionType(pos, parseType());
                       accept(RPAREN);
                       selectExprMode();
                       JCExpression t1 = term3();
                       return F.at(pos).TypeCast(t, t1);
                    case IMPLICIT_LAMBDA:
                    case EXPLICIT_LAMBDA:
                        t = lambdaExpressionOrStatement(true, pres == ParensResult.EXPLICIT_LAMBDA, pos);
                        break;
                    default: //PARENS
                        accept(LPAREN);
                        selectExprMode();
                        t = termRest(term1Rest(term2Rest(term3(), TreeInfo.orPrec)));
                        accept(RPAREN);
                        t = toP(F.at(pos).Parens(t));
                        break;
                }
            } else {
                return illegal();
            }
            break;
        case THIS:
            if (isMode(EXPR)) {
                selectExprMode();
                t = to(F.at(pos).Ident(names._this));
                nextToken();
                if (typeArgs == null)
                    t = argumentsOpt(null, t);
                else
                    t = arguments(typeArgs, t);
                typeArgs = null;
            } else return illegal();
            break;
        case SUPER:
            if (isMode(EXPR)) {
                selectExprMode();
                t = to(F.at(pos).Ident(names._super));
                t = superSuffix(typeArgs, t);
                typeArgs = null;
            } else return illegal();
            break;
        case INTLITERAL: case LONGLITERAL: case FLOATLITERAL: case DOUBLELITERAL:
        case CHARLITERAL: case STRINGLITERAL:
        case TRUE: case FALSE: case NULL:
            if (typeArgs == null && isMode(EXPR)) {
                selectExprMode();
                t = literal(names.empty);
            } else return illegal();
            break;
        case NEW:
            if (typeArgs != null) return illegal();
            if (isMode(EXPR)) {
                selectExprMode();
                nextToken();
                if (token.kind == LT) typeArgs = typeArguments(false);
                t = creator(pos, typeArgs);
                typeArgs = null;
            } else return illegal();
            break;
        case MONKEYS_AT:
            // Only annotated cast types and method references are valid
            List<JCAnnotation> typeAnnos = typeAnnotationsOpt();
            if (typeAnnos.isEmpty()) {
                // else there would be no '@'
                throw new AssertionError("Expected type annotations, but found none!");
            }

            JCExpression expr = term3();

            if (!isMode(TYPE)) {
                // Type annotations on class literals no longer legal
                switch (expr.getTag()) {
                case REFERENCE: {
                    JCMemberReference mref = (JCMemberReference) expr;
                    mref.expr = toP(F.at(pos).AnnotatedType(typeAnnos, mref.expr));
                    t = mref;
                    break;
                }
                case SELECT: {
                    JCFieldAccess sel = (JCFieldAccess) expr;

                    if (sel.name != names._class) {
                        return illegal();
                    } else {
                        log.error(token.pos, Errors.NoAnnotationsOnDotClass);
                        return expr;
                    }
                }
                default:
                    return illegal(typeAnnos.head.pos);
                }

            } else {
                // Type annotations targeting a cast
                t = insertAnnotationsToMostInner(expr, typeAnnos, false);
            }
            break;
        case UNDERSCORE: case IDENTIFIER: case ASSERT: case ENUM:
            if (typeArgs != null) return illegal();
            if (isMode(EXPR) && !isMode(NOLAMBDA) && peekToken(ARROW)) {
                t = lambdaExpressionOrStatement(false, false, pos);
            } else {
                t = toP(F.at(token.pos).Ident(ident()));
                loop: while (true) {
                    pos = token.pos;
                    final List<JCAnnotation> annos = typeAnnotationsOpt();

                    // need to report an error later if LBRACKET is for array
                    // index access rather than array creation level
                    if (!annos.isEmpty() && token.kind != LBRACKET && token.kind != ELLIPSIS)
                        return illegal(annos.head.pos);

                    switch (token.kind) {
                    case LBRACKET:
                        nextToken();
                        if (token.kind == RBRACKET) {
                            nextToken();
                            t = bracketsOpt(t);
                            t = toP(F.at(pos).TypeArray(t));
                            if (annos.nonEmpty()) {
                                t = toP(F.at(pos).AnnotatedType(annos, t));
                            }
                            t = bracketsSuffix(t);
                        } else {
                            if (isMode(EXPR)) {
                                selectExprMode();
                                JCExpression t1 = term();
                                if (!annos.isEmpty()) t = illegal(annos.head.pos);
                                t = to(F.at(pos).Indexed(t, t1));
                            }
                            accept(RBRACKET);
                        }
                        break loop;
                    case LPAREN:
                        if (isMode(EXPR)) {
                            selectExprMode();
                            t = arguments(typeArgs, t);
                            if (!annos.isEmpty()) t = illegal(annos.head.pos);
                            typeArgs = null;
                        }
                        break loop;
                    case DOT:
                        nextToken();
                        if (token.kind == TokenKind.IDENTIFIER && typeArgs != null) {
                            return illegal();
                        }
                        int prevmode = mode;
                        setMode(mode & ~NOPARAMS);
                        typeArgs = typeArgumentsOpt(EXPR);
                        setMode(prevmode);
                        if (isMode(EXPR)) {
                            switch (token.kind) {
                            case CLASS:
                                if (typeArgs != null) return illegal();
                                selectExprMode();
                                t = to(F.at(pos).Select(t, names._class));
                                nextToken();
                                break loop;
                            case THIS:
                                if (typeArgs != null) return illegal();
                                selectExprMode();
                                t = to(F.at(pos).Select(t, names._this));
                                nextToken();
                                break loop;
                            case SUPER:
                                selectExprMode();
                                t = to(F.at(pos).Select(t, names._super));
                                t = superSuffix(typeArgs, t);
                                typeArgs = null;
                                break loop;
                            case NEW:
                                if (typeArgs != null) return illegal();
                                selectExprMode();
                                int pos1 = token.pos;
                                nextToken();
                                if (token.kind == LT) typeArgs = typeArguments(false);
                                t = innerCreator(pos1, typeArgs, t);
                                typeArgs = null;
                                break loop;
                            }
                        }

                        List<JCAnnotation> tyannos = null;
                        if (isMode(TYPE) && token.kind == MONKEYS_AT) {
                            tyannos = typeAnnotationsOpt();
                        }
                        // typeArgs saved for next loop iteration.
                        t = toP(F.at(pos).Select(t, ident()));
                        if (token.pos <= endPosTable.errorEndPos &&
                            token.kind == MONKEYS_AT) {
                            //error recovery, case like:
                            //int i = expr.<missing-ident>
                            //@Deprecated
                            if (typeArgs != null) illegal();
                            return toP(t);
                        }
                        if (tyannos != null && tyannos.nonEmpty()) {
                            t = toP(F.at(tyannos.head.pos).AnnotatedType(tyannos, t));
                        }
                        break;
                    case ELLIPSIS:
                        if (this.permitTypeAnnotationsPushBack) {
                            this.typeAnnotationsPushedBack = annos;
                        } else if (annos.nonEmpty()) {
                            // Don't return here -- error recovery attempt
                            illegal(annos.head.pos);
                        }
                        break loop;
                    case LT:
                        if (!isMode(TYPE) && isUnboundMemberRef()) {
                            //this is an unbound method reference whose qualifier
                            //is a generic type i.e. A<S>::m
                            int pos1 = token.pos;
                            accept(LT);
                            ListBuffer<JCExpression> args = new ListBuffer<>();
                            args.append(typeArgument());
                            while (token.kind == COMMA) {
                                nextToken();
                                args.append(typeArgument());
                            }
                            accept(GT);
                            t = toP(F.at(pos1).TypeApply(t, args.toList()));
                            while (token.kind == DOT) {
                                nextToken();
                                selectTypeMode();
                                t = toP(F.at(token.pos).Select(t, ident()));
                                t = typeArgumentsOpt(t);
                            }
                            t = bracketsOpt(t);
                            if (token.kind != COLCOL) {
                                //method reference expected here
                                t = illegal();
                            }
                            selectExprMode();
                            return term3Rest(t, typeArgs);
                        }
                        break loop;
                    default:
                        break loop;
                    }
                }
            }
            if (typeArgs != null) illegal();
            t = typeArgumentsOpt(t);
            break;
        case BYTE: case SHORT: case CHAR: case INT: case LONG: case FLOAT:
        case DOUBLE: case BOOLEAN:
            if (typeArgs != null) illegal();
            t = bracketsSuffix(bracketsOpt(basicType()));
            break;
        case VOID:
            if (typeArgs != null) illegal();
            if (isMode(EXPR)) {
                nextToken();
                if (token.kind == DOT) {
                    JCPrimitiveTypeTree ti = toP(F.at(pos).TypeIdent(TypeTag.VOID));
                    t = bracketsSuffix(ti);
                } else {
                    return illegal(pos);
                }
            } else {
                // Support the corner case of myMethodHandle.<void>invoke() by passing
                // a void type (like other primitive types) to the next phase.
                // The error will be reported in Attr.attribTypes or Attr.visitApply.
                JCPrimitiveTypeTree ti = to(F.at(pos).TypeIdent(TypeTag.VOID));
                nextToken();
                return ti;
                //return illegal();
            }
            break;
        case SWITCH:
            checkSourceLevel(Feature.SWITCH_EXPRESSION);
            allowYieldStatement = true;
            int switchPos = token.pos;
            nextToken();
            JCExpression selector = parExpression();
            accept(LBRACE);
            ListBuffer<JCCase> cases = new ListBuffer<>();
            while (true) {
                pos = token.pos;
                switch (token.kind) {
                case CASE:
                case DEFAULT:
                    cases.appendList(switchExpressionStatementGroup());
                    break;
                case RBRACE: case EOF:
                    JCSwitchExpression e = to(F.at(switchPos).SwitchExpression(selector,
                                                                               cases.toList()));
                    e.bracePos = token.pos;
                    accept(RBRACE);
                    return e;
                default:
                    nextToken(); // to ensure progress
                    syntaxError(pos, Errors.Expected3(CASE, DEFAULT, RBRACE));
                }
            }
            // Not reachable.
        default:
            return illegal();
        }
        return term3Rest(t, typeArgs);
    }

    private List<JCCase> switchExpressionStatementGroup() {
        ListBuffer<JCCase> caseExprs = new ListBuffer<>();
        int casePos = token.pos;
        ListBuffer<JCCaseLabel> pats = new ListBuffer<>();

        if (token.kind == DEFAULT) {
            nextToken();
            pats.append(toP(F.at(casePos).DefaultCaseLabel()));
        } else {
            accept(CASE);
            boolean allowDefault = false;
            while (true) {
                JCCaseLabel label = parseCaseLabel(allowDefault);
                pats.append(label);
                if (token.kind != COMMA) break;
                checkSourceLevel(Feature.SWITCH_MULTIPLE_CASE_LABELS);
                nextToken();
                allowDefault = TreeInfo.isNullCaseLabel(label);
            };
        }
        JCExpression guard = parseGuard(pats.last());
        List<JCStatement> stats = null;
        JCTree body = null;
        CaseTree.CaseKind kind;
        switch (token.kind) {
            case ARROW:
                checkSourceLevel(Feature.SWITCH_RULE);
                nextToken();
                if (token.kind == TokenKind.THROW || token.kind == TokenKind.LBRACE) {
                    stats = List.of(parseStatement());
                    body = stats.head;
                    kind = JCCase.RULE;
                } else {
                    JCExpression value = parseExpression();
                    stats = List.of(to(F.at(value).Yield(value)));
                    body = value;
                    kind = JCCase.RULE;
                    accept(SEMI);
                }
                break;
            default:
                accept(COLON, tk -> Errors.Expected2(COLON, ARROW));
                stats = blockStatements();
                kind = JCCase.STATEMENT;
                break;
        }
        caseExprs.append(toP(F.at(casePos).Case(kind, pats.toList(), guard, stats, body)));
        return caseExprs.toList();
    }

    JCExpression term3Rest(JCExpression t, List<JCExpression> typeArgs) {
        if (typeArgs != null) illegal();
        while (true) {
            int pos1 = token.pos;
            final List<JCAnnotation> annos = typeAnnotationsOpt();

            if (token.kind == LBRACKET) {
                nextToken();
                if (isMode(TYPE)) {
                    int prevmode = mode;
                    selectTypeMode();
                    if (token.kind == RBRACKET) {
                        nextToken();
                        t = bracketsOpt(t);
                        t = toP(F.at(pos1).TypeArray(t));
                        if (token.kind == COLCOL) {
                            selectExprMode();
                            continue;
                        }
                        if (annos.nonEmpty()) {
                            t = toP(F.at(pos1).AnnotatedType(annos, t));
                        }
                        return t;
                    }
                    setMode(prevmode);
                }
                if (isMode(EXPR)) {
                    selectExprMode();
                    JCExpression t1 = term();
                    t = to(F.at(pos1).Indexed(t, t1));
                }
                accept(RBRACKET);
            } else if (token.kind == DOT) {
                nextToken();
                typeArgs = typeArgumentsOpt(EXPR);
                if (token.kind == SUPER && isMode(EXPR)) {
                    selectExprMode();
                    t = to(F.at(pos1).Select(t, names._super));
                    nextToken();
                    t = arguments(typeArgs, t);
                    typeArgs = null;
                } else if (token.kind == NEW && isMode(EXPR)) {
                    if (typeArgs != null) return illegal();
                    selectExprMode();
                    int pos2 = token.pos;
                    nextToken();
                    if (token.kind == LT) typeArgs = typeArguments(false);
                    t = innerCreator(pos2, typeArgs, t);
                    typeArgs = null;
                } else {
                    List<JCAnnotation> tyannos = null;
                    if (isMode(TYPE) && token.kind == MONKEYS_AT) {
                        // is the mode check needed?
                        tyannos = typeAnnotationsOpt();
                    }
                    t = toP(F.at(pos1).Select(t, ident(true)));
                    if (token.pos <= endPosTable.errorEndPos &&
                        token.kind == MONKEYS_AT) {
                        //error recovery, case like:
                        //int i = expr.<missing-ident>
                        //@Deprecated
                        break;
                    }
                    if (tyannos != null && tyannos.nonEmpty()) {
                        t = toP(F.at(tyannos.head.pos).AnnotatedType(tyannos, t));
                    }
                    t = argumentsOpt(typeArgs, typeArgumentsOpt(t));
                    typeArgs = null;
                }
            } else if (isMode(EXPR) && token.kind == COLCOL) {
                selectExprMode();
                if (typeArgs != null) return illegal();
                accept(COLCOL);
                t = memberReferenceSuffix(pos1, t);
            } else {
                if (!annos.isEmpty()) {
                    if (permitTypeAnnotationsPushBack)
                        typeAnnotationsPushedBack = annos;
                    else
                        return illegal(annos.head.pos);
                }
                break;
            }
        }
        while ((token.kind == PLUSPLUS || token.kind == SUBSUB) && isMode(EXPR)) {
            selectExprMode();
            t = to(F.at(token.pos).Unary(
                  token.kind == PLUSPLUS ? POSTINC : POSTDEC, t));
            nextToken();
        }
        return toP(t);
    }

    /**
     * If we see an identifier followed by a '&lt;' it could be an unbound
     * method reference or a binary expression. To disambiguate, look for a
     * matching '&gt;' and see if the subsequent terminal is either '.' or '::'.
     */
    @SuppressWarnings("fallthrough")
    boolean isUnboundMemberRef() {
        int pos = 0, depth = 0;
        outer: for (Token t = S.token(pos) ; ; t = S.token(++pos)) {
            switch (t.kind) {
                case IDENTIFIER: case UNDERSCORE: case QUES: case EXTENDS: case SUPER:
                case DOT: case RBRACKET: case LBRACKET: case COMMA:
                case BYTE: case SHORT: case INT: case LONG: case FLOAT:
                case DOUBLE: case BOOLEAN: case CHAR:
                case MONKEYS_AT:
                    break;

                case LPAREN:
                    // skip annotation values
                    int nesting = 0;
                    for (; ; pos++) {
                        TokenKind tk2 = S.token(pos).kind;
                        switch (tk2) {
                            case EOF:
                                return false;
                            case LPAREN:
                                nesting++;
                                break;
                            case RPAREN:
                                nesting--;
                                if (nesting == 0) {
                                    continue outer;
                                }
                                break;
                        }
                    }

                case LT:
                    depth++; break;
                case GTGTGT:
                    depth--;
                case GTGT:
                    depth--;
                case GT:
                    depth--;
                    if (depth == 0) {
                        TokenKind nextKind = S.token(pos + 1).kind;
                        return
                            nextKind == TokenKind.DOT ||
                            nextKind == TokenKind.LBRACKET ||
                            nextKind == TokenKind.COLCOL;
                    }
                    break;
                default:
                    return false;
            }
        }
    }

    /**
     * If we see an identifier followed by a '&lt;' it could be an unbound
     * method reference or a binary expression. To disambiguate, look for a
     * matching '&gt;' and see if the subsequent terminal is either '.' or '::'.
     */
    @SuppressWarnings("fallthrough")
    ParensResult analyzeParens() {
        int depth = 0;
        boolean type = false;
        ParensResult defaultResult = ParensResult.PARENS;
        outer: for (int lookahead = 0; ; lookahead++) {
            TokenKind tk = S.token(lookahead).kind;
            switch (tk) {
                case COMMA:
                    type = true;
                case EXTENDS: case SUPER: case DOT: case AMP:
                    //skip
                    break;
                case QUES:
                    if (peekToken(lookahead, EXTENDS) ||
                            peekToken(lookahead, SUPER)) {
                        //wildcards
                        type = true;
                    }
                    break;
                case BYTE: case SHORT: case INT: case LONG: case FLOAT:
                case DOUBLE: case BOOLEAN: case CHAR: case VOID:
                    if (peekToken(lookahead, RPAREN)) {
                        //Type, ')' -> cast
                        return ParensResult.CAST;
                    } else if (peekToken(lookahead, LAX_IDENTIFIER)) {
                        //Type, Identifier/'_'/'assert'/'enum' -> explicit lambda
                        return ParensResult.EXPLICIT_LAMBDA;
                    }
                    break;
                case LPAREN:
                    if (lookahead != 0) {
                        // '(' in a non-starting position -> parens
                        return ParensResult.PARENS;
                    } else if (peekToken(lookahead, RPAREN)) {
                        // '(', ')' -> explicit lambda
                        return ParensResult.EXPLICIT_LAMBDA;
                    }
                    break;
                case RPAREN:
                    // if we have seen something that looks like a type,
                    // then it's a cast expression
                    if (type) return ParensResult.CAST;
                    // otherwise, disambiguate cast vs. parenthesized expression
                    // based on subsequent token.
                    switch (S.token(lookahead + 1).kind) {
                        /*case PLUSPLUS: case SUBSUB: */
                        case BANG: case TILDE:
                        case LPAREN: case THIS: case SUPER:
                        case INTLITERAL: case LONGLITERAL: case FLOATLITERAL:
                        case DOUBLELITERAL: case CHARLITERAL: case STRINGLITERAL:
                        case STRINGFRAGMENT:
                        case TRUE: case FALSE: case NULL:
                        case NEW: case IDENTIFIER: case ASSERT: case ENUM: case UNDERSCORE:
                        case SWITCH:
                        case BYTE: case SHORT: case CHAR: case INT:
                        case LONG: case FLOAT: case DOUBLE: case BOOLEAN: case VOID:
                            return ParensResult.CAST;
                        default:
                            return defaultResult;
                    }
                case UNDERSCORE:
                case ASSERT:
                case ENUM:
                case IDENTIFIER:
                    if (peekToken(lookahead, LAX_IDENTIFIER)) {
                        // Identifier, Identifier/'_'/'assert'/'enum' -> explicit lambda
                        return ParensResult.EXPLICIT_LAMBDA;
                    } else if (peekToken(lookahead, RPAREN, ARROW)) {
                        // Identifier, ')' '->' -> implicit lambda
                        return !isMode(NOLAMBDA) ? ParensResult.IMPLICIT_LAMBDA
                                                 : ParensResult.PARENS;
                    } else if (depth == 0 && peekToken(lookahead, COMMA)) {
                        defaultResult = ParensResult.IMPLICIT_LAMBDA;
                    }
                    type = false;
                    break;
                case FINAL:
                case ELLIPSIS:
                    //those can only appear in explicit lambdas
                    return ParensResult.EXPLICIT_LAMBDA;
                case MONKEYS_AT:
                    type = true;
                    lookahead = skipAnnotation(lookahead);
                    break;
                case LBRACKET:
                    if (peekToken(lookahead, RBRACKET, LAX_IDENTIFIER)) {
                        // '[', ']', Identifier/'_'/'assert'/'enum' -> explicit lambda
                        return ParensResult.EXPLICIT_LAMBDA;
                    } else if (peekToken(lookahead, RBRACKET, RPAREN) ||
                            peekToken(lookahead, RBRACKET, AMP)) {
                        // '[', ']', ')' -> cast
                        // '[', ']', '&' -> cast (intersection type)
                        return ParensResult.CAST;
                    } else if (peekToken(lookahead, RBRACKET)) {
                        //consume the ']' and skip
                        type = true;
                        lookahead++;
                        break;
                    } else {
                        return ParensResult.PARENS;
                    }
                case LT:
                    depth++; break;
                case GTGTGT:
                    depth--;
                case GTGT:
                    depth--;
                case GT:
                    depth--;
                    if (depth == 0) {
                        if (peekToken(lookahead, RPAREN) ||
                                peekToken(lookahead, AMP)) {
                            // '>', ')' -> cast
                            // '>', '&' -> cast
                            return ParensResult.CAST;
                        } else if (peekToken(lookahead, LAX_IDENTIFIER, COMMA) ||
                                peekToken(lookahead, LAX_IDENTIFIER, RPAREN, ARROW) ||
                                peekToken(lookahead, ELLIPSIS)) {
                            // '>', Identifier/'_'/'assert'/'enum', ',' -> explicit lambda
                            // '>', Identifier/'_'/'assert'/'enum', ')', '->' -> explicit lambda
                            // '>', '...' -> explicit lambda
                            return ParensResult.EXPLICIT_LAMBDA;
                        }
                        //it looks a type, but could still be (i) a cast to generic type,
                        //(ii) an unbound method reference or (iii) an explicit lambda
                        type = true;
                        break;
                    } else if (depth < 0) {
                        //unbalanced '<', '>' - not a generic type
                        return ParensResult.PARENS;
                    }
                    break;
                default:
                    //this includes EOF
                    return defaultResult;
            }
        }
    }

    private int skipAnnotation(int lookahead) {
        lookahead += 1; //skip '@'
        while (peekToken(lookahead, DOT)) {
            lookahead += 2;
        }
        if (peekToken(lookahead, LPAREN)) {
            lookahead++;
            //skip annotation values
            int nesting = 0;
            for (; ; lookahead++) {
                TokenKind tk2 = S.token(lookahead).kind;
                switch (tk2) {
                    case EOF:
                        return lookahead;
                    case LPAREN:
                        nesting++;
                        break;
                    case RPAREN:
                        nesting--;
                        if (nesting == 0) {
                            return lookahead;
                        }
                    break;
                }
            }
        }
        return lookahead;
    }

    /** Accepts all identifier-like tokens */
    protected Predicate<TokenKind> LAX_IDENTIFIER = t -> t == IDENTIFIER || t == UNDERSCORE || t == ASSERT || t == ENUM;

    enum ParensResult {
        CAST,
        EXPLICIT_LAMBDA,
        IMPLICIT_LAMBDA,
        PARENS
    }

    JCExpression lambdaExpressionOrStatement(boolean hasParens, boolean explicitParams, int pos) {
        List<JCVariableDecl> params = explicitParams ?
                formalParameters(true, false) :
                implicitParameters(hasParens);
        if (explicitParams) {
            LambdaClassifier lambdaClassifier = new LambdaClassifier();
            for (JCVariableDecl param: params) {
                Name restrictedTypeName;
                if (param.vartype != null &&
                        (restrictedTypeName = restrictedTypeName(param.vartype, false)) != null &&
                        param.vartype.hasTag(TYPEARRAY)) {
                    log.error(DiagnosticFlag.SYNTAX, param.pos,
                        Feature.VAR_SYNTAX_IMPLICIT_LAMBDAS.allowedInSource(source)
                            ? Errors.RestrictedTypeNotAllowedArray(restrictedTypeName) : Errors.RestrictedTypeNotAllowedHere(restrictedTypeName));
                }
                lambdaClassifier.addParameter(param);
                if (lambdaClassifier.result() == LambdaParameterKind.ERROR) {
                    break;
                }
            }
            if (lambdaClassifier.diagFragment != null) {
                log.error(DiagnosticFlag.SYNTAX, pos, Errors.InvalidLambdaParameterDeclaration(lambdaClassifier.diagFragment));
            }
            for (JCVariableDecl param: params) {
                if (param.vartype != null
                        && restrictedTypeName(param.vartype, true) != null) {
                    checkSourceLevel(param.pos, Feature.VAR_SYNTAX_IMPLICIT_LAMBDAS);
                    param.startPos = TreeInfo.getStartPos(param.vartype);
                    param.vartype = null;
                }
            }
        }
        return lambdaExpressionOrStatementRest(params, pos);
    }

    enum LambdaParameterKind {
        VAR(0),
        EXPLICIT(1),
        IMPLICIT(2),
        ERROR(-1);

        private final int index;

        LambdaParameterKind(int index) {
            this.index = index;
        }
    }

    private static final Fragment[][] decisionTable = new Fragment[][] {
        /*              VAR                              EXPLICIT                         IMPLICIT  */
        /* VAR      */ {null,                            VarAndExplicitNotAllowed,        VarAndImplicitNotAllowed},
        /* EXPLICIT */ {VarAndExplicitNotAllowed,        null,                            ImplicitAndExplicitNotAllowed},
        /* IMPLICIT */ {VarAndImplicitNotAllowed,        ImplicitAndExplicitNotAllowed,   null},
    };

    class LambdaClassifier {
        LambdaParameterKind kind;
        Fragment diagFragment;

        /**
         * analyzeParens() has already classified the lambda as EXPLICIT_LAMBDA, due to
         * two consecutive identifiers. Because of that {@code (<explicit lambda>)}, the
         * parser will always attempt to parse a type, followed by a name. If the lambda
         * contains an illegal mix of implicit and explicit parameters, it is possible
         * for the parser to see a {@code ,} when expecting a name, in which case the
         * variable is created with an erroneous name. The logic below makes sure that
         * the lambda parameters are all declared with either an explicit type (e.g.
         * {@code String x}), or with an inferred type (using {@code var x}). Any other
         * combination is rejected.
         * */
        void addParameter(JCVariableDecl param) {
            Assert.check(param.vartype != null);

            if (param.name == names.error) {
                reduce(LambdaParameterKind.IMPLICIT);
            }
            else if (restrictedTypeName(param.vartype, false) != null) {
                reduce(LambdaParameterKind.VAR);
            } else {
                reduce(LambdaParameterKind.EXPLICIT);
            }
        }

        private void reduce(LambdaParameterKind newKind) {
            if (kind == null) {
                kind = newKind;
            } else if (kind != newKind && kind != LambdaParameterKind.ERROR) {
                LambdaParameterKind currentKind = kind;
                kind = LambdaParameterKind.ERROR;
                boolean varIndex = currentKind.index == LambdaParameterKind.VAR.index ||
                        newKind.index == LambdaParameterKind.VAR.index;
                diagFragment = Feature.VAR_SYNTAX_IMPLICIT_LAMBDAS.allowedInSource(source) || !varIndex ?
                        decisionTable[currentKind.index][newKind.index] : null;
            }
        }

        LambdaParameterKind result() {
            return kind;
        }
    }

    JCExpression lambdaExpressionOrStatementRest(List<JCVariableDecl> args, int pos) {
        accept(ARROW);

        return token.kind == LBRACE ?
            lambdaStatement(args, pos, token.pos) :
            lambdaExpression(args, pos);
    }

    JCExpression lambdaStatement(List<JCVariableDecl> args, int pos, int pos2) {
        JCBlock block = block(pos2, 0);
        return toP(F.at(pos).Lambda(args, block));
    }

    JCExpression lambdaExpression(List<JCVariableDecl> args, int pos) {
        JCTree expr = parseExpression();
        return toP(F.at(pos).Lambda(args, expr));
    }

    /** SuperSuffix = Arguments | "." [TypeArguments] Ident [Arguments]
     */
    JCExpression superSuffix(List<JCExpression> typeArgs, JCExpression t) {
        nextToken();
        if (token.kind == LPAREN || typeArgs != null) {
            t = arguments(typeArgs, t);
        } else if (token.kind == COLCOL) {
            if (typeArgs != null) return illegal();
            t = memberReferenceSuffix(t);
        } else {
            int pos = token.pos;
            accept(DOT);
            typeArgs = (token.kind == LT) ? typeArguments(false) : null;
            t = toP(F.at(pos).Select(t, ident()));
            t = argumentsOpt(typeArgs, t);
        }
        return t;
    }

    /** BasicType = BYTE | SHORT | CHAR | INT | LONG | FLOAT | DOUBLE | BOOLEAN
     */
    JCPrimitiveTypeTree basicType() {
        JCPrimitiveTypeTree t = to(F.at(token.pos).TypeIdent(typetag(token.kind)));
        nextToken();
        return t;
    }

    /** ArgumentsOpt = [ Arguments ]
     */
    JCExpression argumentsOpt(List<JCExpression> typeArgs, JCExpression t) {
        if (isMode(EXPR) && token.kind == LPAREN || typeArgs != null) {
            selectExprMode();
            return arguments(typeArgs, t);
        } else {
            return t;
        }
    }

    /** Arguments = "(" [Expression { COMMA Expression }] ")"
     */
    List<JCExpression> arguments() {
        ListBuffer<JCExpression> args = new ListBuffer<>();
        if (token.kind == LPAREN) {
            nextToken();
            if (token.kind != RPAREN) {
                args.append(parseExpression());
                while (token.kind == COMMA) {
                    nextToken();
                    args.append(parseExpression());
                }
            }
            accept(RPAREN, tk -> Errors.Expected2(RPAREN, COMMA));
        } else {
            syntaxError(token.pos, Errors.Expected(LPAREN));
        }
        return args.toList();
    }

    JCExpression arguments(List<JCExpression> typeArgs, JCExpression t) {
        int pos = token.pos;
        List<JCExpression> args = arguments();
        JCExpression mi = F.at(pos).Apply(typeArgs, t, args);
        if (t.hasTag(IDENT) && isInvalidUnqualifiedMethodIdentifier(((JCIdent) t).pos,
                                                                    ((JCIdent) t).name)) {
            log.error(DiagnosticFlag.SYNTAX, t, Errors.InvalidYield);
            mi = F.Erroneous(List.of(mi));
        }
        return toP(mi);
    }

    boolean isInvalidUnqualifiedMethodIdentifier(int pos, Name name) {
        if (name == names.yield) {
            if (allowYieldStatement) {
                return true;
            } else {
                log.warning(pos, Warnings.InvalidYield);
            }
        }
        return false;
    }

    /**  TypeArgumentsOpt = [ TypeArguments ]
     */
    JCExpression typeArgumentsOpt(JCExpression t) {
        if (token.kind == LT &&
            isMode(TYPE) &&
            !isMode(NOPARAMS)) {
            selectTypeMode();
            return typeArguments(t, false);
        } else {
            return t;
        }
    }
    List<JCExpression> typeArgumentsOpt() {
        return typeArgumentsOpt(TYPE);
    }

    List<JCExpression> typeArgumentsOpt(int useMode) {
        if (token.kind == LT) {
            if (!isMode(useMode) ||
                isMode(NOPARAMS)) {
                illegal();
            }
            setMode(useMode);
            return typeArguments(false);
        }
        return null;
    }

    /**
     *  {@literal
     *  TypeArguments  = "<" TypeArgument {"," TypeArgument} ">"
     *  }
     */
    List<JCExpression> typeArguments(boolean diamondAllowed) {
        if (token.kind == LT) {
            nextToken();
            if (token.kind == GT && diamondAllowed) {
                setMode(mode | DIAMOND);
                nextToken();
                return List.nil();
            } else {
                ListBuffer<JCExpression> args = new ListBuffer<>();
                args.append(!isMode(EXPR) ? typeArgument() : parseType());
                while (token.kind == COMMA) {
                    nextToken();
                    args.append(!isMode(EXPR) ? typeArgument() : parseType());
                }
                switch (token.kind) {

                case GTGTGTEQ: case GTGTEQ: case GTEQ:
                case GTGTGT: case GTGT:
                    token = S.split();
                    break;
                case GT:
                    nextToken();
                    break;
                default:
                    args.append(syntaxError(token.pos, Errors.Expected2(GT, COMMA)));
                    break;
                }
                return args.toList();
            }
        } else {
            return List.of(syntaxError(token.pos, Errors.Expected(LT)));
        }
    }

    /**
     *  {@literal
     *  TypeArgument = Type
     *               | [Annotations] "?"
     *               | [Annotations] "?" EXTENDS Type {"&" Type}
     *               | [Annotations] "?" SUPER Type
     *  }
     */
    JCExpression typeArgument() {
        List<JCAnnotation> annotations = typeAnnotationsOpt();
        if (token.kind != QUES) return parseType(false, annotations);
        int pos = token.pos;
        nextToken();
        JCExpression result;
        if (token.kind == EXTENDS) {
            TypeBoundKind t = to(F.at(pos).TypeBoundKind(BoundKind.EXTENDS));
            nextToken();
            JCExpression bound = parseType();
            result = F.at(pos).Wildcard(t, bound);
        } else if (token.kind == SUPER) {
            TypeBoundKind t = to(F.at(pos).TypeBoundKind(BoundKind.SUPER));
            nextToken();
            JCExpression bound = parseType();
            result = F.at(pos).Wildcard(t, bound);
        } else if (LAX_IDENTIFIER.test(token.kind)) {
            //error recovery
            TypeBoundKind t = F.at(Position.NOPOS).TypeBoundKind(BoundKind.UNBOUND);
            JCExpression wc = toP(F.at(pos).Wildcard(t, null));
            JCIdent id = toP(F.at(token.pos).Ident(ident()));
            JCErroneous err = F.at(pos).Erroneous(List.<JCTree>of(wc, id));
            reportSyntaxError(err, Errors.Expected3(GT, EXTENDS, SUPER));
            result = err;
        } else {
            TypeBoundKind t = toP(F.at(pos).TypeBoundKind(BoundKind.UNBOUND));
            result = toP(F.at(pos).Wildcard(t, null));
        }
        if (!annotations.isEmpty()) {
            result = toP(F.at(annotations.head.pos).AnnotatedType(annotations,result));
        }
        return result;
    }

    JCTypeApply typeArguments(JCExpression t, boolean diamondAllowed) {
        int pos = token.pos;
        List<JCExpression> args = typeArguments(diamondAllowed);
        return toP(F.at(pos).TypeApply(t, args));
    }

    /**
     * BracketsOpt = { [Annotations] "[" "]" }*
     *
     * <p>
     *
     * <code>annotations</code> is the list of annotations targeting
     * the expression <code>t</code>.
     */
    private JCExpression bracketsOpt(JCExpression t,
            List<JCAnnotation> annotations) {
        List<JCAnnotation> nextLevelAnnotations = typeAnnotationsOpt();

        if (token.kind == LBRACKET) {
            int pos = token.pos;
            nextToken();
            t = bracketsOptCont(t, pos, nextLevelAnnotations);
        } else if (!nextLevelAnnotations.isEmpty()) {
            if (permitTypeAnnotationsPushBack) {
                this.typeAnnotationsPushedBack = nextLevelAnnotations;
            } else {
                return illegal(nextLevelAnnotations.head.pos);
            }
        }

        if (!annotations.isEmpty()) {
            t = toP(F.at(token.pos).AnnotatedType(annotations, t));
        }
        return t;
    }

    /** BracketsOpt = [ "[" "]" { [Annotations] "[" "]"} ]
     */
    private JCExpression bracketsOpt(JCExpression t) {
        return bracketsOpt(t, List.nil());
    }

    private JCExpression bracketsOptCont(JCExpression t, int pos,
            List<JCAnnotation> annotations) {
        accept(RBRACKET);
        t = bracketsOpt(t);
        t = toP(F.at(pos).TypeArray(t));
        if (annotations.nonEmpty()) {
            t = toP(F.at(pos).AnnotatedType(annotations, t));
        }
        return t;
    }

    /** BracketsSuffixExpr = "." CLASS
     *  BracketsSuffixType =
     */
    JCExpression bracketsSuffix(JCExpression t) {
        if (isMode(EXPR) && token.kind == DOT) {
            selectExprMode();
            int pos = token.pos;
            nextToken();
            accept(CLASS);
            if (token.pos == endPosTable.errorEndPos) {
                // error recovery
                Name name;
                if (LAX_IDENTIFIER.test(token.kind)) {
                    name = token.name();
                    nextToken();
                } else {
                    name = names.error;
                }
                t = F.at(pos).Erroneous(List.<JCTree>of(toP(F.at(pos).Select(t, name))));
            } else {
                Tag tag = t.getTag();
                // Type annotations are illegal on class literals. Annotated non array class literals
                // are complained about directly in term3(), Here check for type annotations on dimensions
                // taking care to handle some interior dimension(s) being annotated.
                if ((tag == TYPEARRAY && TreeInfo.containsTypeAnnotation(t)) || tag == ANNOTATED_TYPE)
                    syntaxError(token.pos, Errors.NoAnnotationsOnDotClass);
                t = toP(F.at(pos).Select(t, names._class));
            }
        } else if (isMode(TYPE)) {
            if (token.kind != COLCOL) {
                selectTypeMode();
            }
        } else if (token.kind != COLCOL) {
            syntaxError(token.pos, Errors.DotClassExpected);
        }
        return t;
    }

    /**
     * MemberReferenceSuffix = "::" [TypeArguments] Ident
     *                       | "::" [TypeArguments] "new"
     */
    JCExpression memberReferenceSuffix(JCExpression t) {
        int pos1 = token.pos;
        accept(COLCOL);
        return memberReferenceSuffix(pos1, t);
    }

    JCExpression memberReferenceSuffix(int pos1, JCExpression t) {
        selectExprMode();
        List<JCExpression> typeArgs = null;
        if (token.kind == LT) {
            typeArgs = typeArguments(false);
        }
        Name refName;
        ReferenceMode refMode;
        if (token.kind == NEW) {
            refMode = ReferenceMode.NEW;
            refName = names.init;
            nextToken();
        } else {
            refMode = ReferenceMode.INVOKE;
            refName = ident();
        }
        return toP(F.at(t.getStartPosition()).Reference(refMode, refName, t, typeArgs));
    }

    /** Creator = [Annotations] Qualident [TypeArguments] ( ArrayCreatorRest | ClassCreatorRest )
     */
    JCExpression creator(int newpos, List<JCExpression> typeArgs) {
        List<JCAnnotation> newAnnotations = typeAnnotationsOpt();

        switch (token.kind) {
        case BYTE: case SHORT: case CHAR: case INT: case LONG: case FLOAT:
        case DOUBLE: case BOOLEAN:
            if (typeArgs == null) {
                if (newAnnotations.isEmpty()) {
                    return arrayCreatorRest(newpos, basicType());
                } else {
                    return arrayCreatorRest(newpos, toP(F.at(newAnnotations.head.pos).AnnotatedType(newAnnotations, basicType())));
                }
            }
            break;
        default:
        }
        JCExpression t = qualident(true);

        int prevmode = mode;
        selectTypeMode();
        boolean diamondFound = false;
        int lastTypeargsPos = -1;
        if (token.kind == LT) {
            lastTypeargsPos = token.pos;
            t = typeArguments(t, true);
            diamondFound = isMode(DIAMOND);
        }
        while (token.kind == DOT) {
            if (diamondFound) {
                //cannot select after a diamond
                illegal();
            }
            int pos = token.pos;
            nextToken();
            List<JCAnnotation> tyannos = typeAnnotationsOpt();
            t = toP(F.at(pos).Select(t, ident()));

            if (tyannos != null && tyannos.nonEmpty()) {
                t = toP(F.at(tyannos.head.pos).AnnotatedType(tyannos, t));
            }

            if (token.kind == LT) {
                lastTypeargsPos = token.pos;
                t = typeArguments(t, true);
                diamondFound = isMode(DIAMOND);
            }
        }
        setMode(prevmode);
        if (token.kind == LBRACKET || token.kind == MONKEYS_AT) {
            // handle type annotations for non primitive arrays
            if (newAnnotations.nonEmpty()) {
                t = insertAnnotationsToMostInner(t, newAnnotations, false);
            }

            JCExpression e = arrayCreatorRest(newpos, t);
            if (diamondFound) {
                reportSyntaxError(lastTypeargsPos, Errors.CannotCreateArrayWithDiamond);
                return toP(F.at(newpos).Erroneous(List.of(e)));
            }
            else if (typeArgs != null) {
                int pos = newpos;
                if (!typeArgs.isEmpty() && typeArgs.head.pos != Position.NOPOS) {
                    // note: this should always happen but we should
                    // not rely on this as the parser is continuously
                    // modified to improve error recovery.
                    pos = typeArgs.head.pos;
                }
                setErrorEndPos(S.prevToken().endPos);
                JCErroneous err = F.at(pos).Erroneous(typeArgs.prepend(e));
                reportSyntaxError(err, Errors.CannotCreateArrayWithTypeArguments);
                return toP(err);
            }
            return e;
        } else if (token.kind == LPAREN) {
            // handle type annotations for instantiations and anonymous classes
            if (newAnnotations.nonEmpty()) {
                t = insertAnnotationsToMostInner(t, newAnnotations, false);
            }
            return classCreatorRest(newpos, null, typeArgs, t);
        } else {
            setErrorEndPos(token.pos);
            reportSyntaxError(token.pos, Errors.Expected2(LPAREN, LBRACKET));
            t = toP(F.at(newpos).NewClass(null, typeArgs, t, List.nil(), null));
            return toP(F.at(newpos).Erroneous(List.<JCTree>of(t)));
        }
    }

    /** InnerCreator = [Annotations] Ident [TypeArguments] ClassCreatorRest
     */
    JCExpression innerCreator(int newpos, List<JCExpression> typeArgs, JCExpression encl) {
        List<JCAnnotation> newAnnotations = typeAnnotationsOpt();

        JCExpression t = toP(F.at(token.pos).Ident(ident()));

        if (newAnnotations.nonEmpty()) {
            t = toP(F.at(newAnnotations.head.pos).AnnotatedType(newAnnotations, t));
        }

        if (token.kind == LT) {
            int prevmode = mode;
            t = typeArguments(t, true);
            setMode(prevmode);
        }
        return classCreatorRest(newpos, encl, typeArgs, t);
    }

    /** ArrayCreatorRest = [Annotations] "[" ( "]" BracketsOpt ArrayInitializer
     *                         | Expression "]" {[Annotations]  "[" Expression "]"} BracketsOpt )
     */
    JCExpression arrayCreatorRest(int newpos, JCExpression elemtype) {
        List<JCAnnotation> annos = typeAnnotationsOpt();

        accept(LBRACKET);
        if (token.kind == RBRACKET) {
            accept(RBRACKET);
            elemtype = bracketsOpt(elemtype, annos);
            if (token.kind == LBRACE) {
                JCNewArray na = (JCNewArray)arrayInitializer(newpos, elemtype);
                if (annos.nonEmpty()) {
                    // when an array initializer is present then
                    // the parsed annotations should target the
                    // new array tree
                    // bracketsOpt inserts the annotation in
                    // elemtype, and it needs to be corrected
                    //
                    JCAnnotatedType annotated = (JCAnnotatedType)elemtype;
                    assert annotated.annotations == annos;
                    na.annotations = annotated.annotations;
                    na.elemtype = annotated.underlyingType;
                }
                return na;
            } else {
                JCExpression t = toP(F.at(newpos).NewArray(elemtype, List.nil(), null));
                return syntaxError(token.pos, List.of(t), Errors.ArrayDimensionMissing);
            }
        } else {
            ListBuffer<JCExpression> dims = new ListBuffer<>();

            // maintain array dimension type annotations
            ListBuffer<List<JCAnnotation>> dimAnnotations = new ListBuffer<>();
            dimAnnotations.append(annos);

            dims.append(parseExpression());
            accept(RBRACKET);
            while (token.kind == LBRACKET
                    || token.kind == MONKEYS_AT) {
                List<JCAnnotation> maybeDimAnnos = typeAnnotationsOpt();
                int pos = token.pos;
                nextToken();
                if (token.kind == RBRACKET) { // no dimension
                    elemtype = bracketsOptCont(elemtype, pos, maybeDimAnnos);
                } else {
                    dimAnnotations.append(maybeDimAnnos);
                    dims.append(parseExpression());
                    accept(RBRACKET);
                }
            }

            List<JCExpression> elems = null;
            int errpos = token.pos;

            if (token.kind == LBRACE) {
                elems = arrayInitializerElements(newpos, elemtype);
            }

            JCNewArray na = toP(F.at(newpos).NewArray(elemtype, dims.toList(), elems));
            na.dimAnnotations = dimAnnotations.toList();

            if (elems != null) {
                return syntaxError(errpos, List.of(na), Errors.IllegalArrayCreationBothDimensionAndInitialization);
            }

            return na;
        }
    }

    /** ClassCreatorRest = Arguments [ClassBody]
     */
    JCNewClass classCreatorRest(int newpos,
                                  JCExpression encl,
                                  List<JCExpression> typeArgs,
                                  JCExpression t)
    {
        List<JCExpression> args = arguments();
        JCClassDecl body = null;
        if (token.kind == LBRACE) {
            ignoreDanglingComments(); // ignore any comments from before the '{'
            int pos = token.pos;
            List<JCTree> defs = classInterfaceOrRecordBody(names.empty, false, false);
            JCModifiers mods = F.at(Position.NOPOS).Modifiers(0);
            body = toP(F.at(pos).AnonymousClassDef(mods, defs));
        }
        return toP(F.at(newpos).NewClass(encl, typeArgs, t, args, body));
    }

    /** ArrayInitializer = "{" [VariableInitializer {"," VariableInitializer}] [","] "}"
     */
    JCExpression arrayInitializer(int newpos, JCExpression t) {
        List<JCExpression> elems = arrayInitializerElements(newpos, t);
        return toP(F.at(newpos).NewArray(t, List.nil(), elems));
    }

    List<JCExpression> arrayInitializerElements(int newpos, JCExpression t) {
        accept(LBRACE);
        ListBuffer<JCExpression> elems = new ListBuffer<>();
        if (token.kind == COMMA) {
            nextToken();
        } else if (token.kind != RBRACE) {
            elems.append(variableInitializer());
            while (token.kind == COMMA) {
                nextToken();
                if (token.kind == RBRACE) break;
                elems.append(variableInitializer());
            }
        }
        accept(RBRACE);
        return elems.toList();
    }

    /** VariableInitializer = ArrayInitializer | Expression
     */
    public JCExpression variableInitializer() {
        return token.kind == LBRACE ? arrayInitializer(token.pos, null) : parseExpression();
    }

    /** ParExpression = "(" Expression ")"
     */
    JCExpression parExpression() {
        int pos = token.pos;
        accept(LPAREN);
        JCExpression t = parseExpression();
        accept(RPAREN);
        return toP(F.at(pos).Parens(t));
    }

    /** Block = "{" BlockStatements "}"
     */
    JCBlock block(int pos, long flags) {
        accept(LBRACE);
        ignoreDanglingComments();   // ignore any comments from before the '{'
        List<JCStatement> stats = blockStatements();
        JCBlock t = F.at(pos).Block(flags, stats);
        while (token.kind == CASE || token.kind == DEFAULT) {
            syntaxError(token.pos, Errors.Orphaned(token.kind));
            switchBlockStatementGroups();
        }
        // the Block node has a field "bracePos" for first char of last token, which is
        // usually but not necessarily the last char of the last token.
        t.bracePos = token.pos;
        accept(RBRACE);
        return toP(t);
    }

    public JCBlock block() {
        return block(token.pos, 0);
    }

    /** BlockStatements = { BlockStatement }
     *  BlockStatement  = LocalVariableDeclarationStatement
     *                  | ClassOrInterfaceOrEnumDeclaration
     *                  | [Ident ":"] Statement
     *  LocalVariableDeclarationStatement
     *                  = { FINAL | '@' Annotation } Type VariableDeclarators ";"
     */
    List<JCStatement> blockStatements() {
        //todo: skip to anchor on error(?)
        int lastErrPos = -1;
        ListBuffer<JCStatement> stats = new ListBuffer<>();
        while (true) {
            List<JCStatement> stat = blockStatement();
            ignoreDanglingComments();  // ignore comments not consumed by the statement
            if (stat.isEmpty()) {
                return stats.toList();
            } else {
                // error recovery
                if (token.pos == lastErrPos)
                    return stats.toList();
                if (token.pos <= endPosTable.errorEndPos) {
                    skip(false, true, true, true);
                    lastErrPos = token.pos;
                }
                stats.addAll(stat);
            }
        }
    }

    /*
     * Parse a Statement (JLS 14.5). As an enhancement to improve error recovery,
     * this method will also recognize variable and class declarations (which are
     * not legal for a Statement) by delegating the parsing to BlockStatement (JLS 14.2).
     * If any illegal declarations are found, they will be wrapped in an erroneous tree,
     * and an error will be produced by this method.
     */
    JCStatement parseStatementAsBlock() {
        int pos = token.pos;
        List<JCStatement> stats = blockStatement();
        if (stats.isEmpty()) {
            JCErroneous e = syntaxError(pos, Errors.IllegalStartOfStmt);
            return toP(F.at(pos).Exec(e));
        } else {
            JCStatement first = stats.head;
            Error error = null;
            switch (first.getTag()) {
            case CLASSDEF:
                error = Errors.ClassNotAllowed;
                break;
            case VARDEF:
                error = Errors.VariableNotAllowed;
                break;
            }
            if (error != null) {
                log.error(DiagnosticFlag.SYNTAX, first, error);
                List<JCBlock> blist = List.of(F.at(first.pos).Block(0, stats));
                return toP(F.at(pos).Exec(F.at(first.pos).Erroneous(blist)));
            }
            return first;
        }
    }

    /**This method parses a statement appearing inside a block.
     */
    List<JCStatement> blockStatement() {
        //todo: skip to anchor on error(?)
        Comment dc;
        int pos = token.pos;
        switch (token.kind) {
        case RBRACE: case CASE: case DEFAULT: case EOF:
            return List.nil();
        case LBRACE: case IF: case FOR: case WHILE: case DO: case TRY:
        case SWITCH: case SYNCHRONIZED: case RETURN: case FASTRETURN: case THROW: case BREAK:
        case CONTINUE: case SEMI: case ELSE: case FINALLY: case CATCH:
        case ASSERT:
            return List.of(parseSimpleStatement());
        case MONKEYS_AT:
        case FINAL: {
            dc = token.docComment();
            JCModifiers mods = modifiersOpt();
            if (isDeclaration()) {
                return List.of(classOrRecordOrInterfaceOrEnumDeclaration(mods, dc));
            } else {
                JCExpression t = parseType(true);
                return localVariableDeclarations(mods, t, dc);
            }
        }
        case ABSTRACT: case STRICTFP: {
            dc = token.docComment();
            JCModifiers mods = modifiersOpt();
            return List.of(classOrRecordOrInterfaceOrEnumDeclaration(mods, dc));
        }
        case INTERFACE:
        case CLASS:
            dc = token.docComment();
            return List.of(classOrRecordOrInterfaceOrEnumDeclaration(modifiersOpt(), dc));
        case ENUM:
            if (!allowRecords) {
                log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.LocalEnum);
            }
            dc = token.docComment();
            return List.of(classOrRecordOrInterfaceOrEnumDeclaration(modifiersOpt(), dc));
        case IDENTIFIER:
            if (token.name() == names.yield && allowYieldStatement) {
                Token next = S.token(1);
                boolean isYieldStatement;
                switch (next.kind) {
                    case PLUS: case SUB: case STRINGLITERAL: case CHARLITERAL:
                    case STRINGFRAGMENT:
                    case INTLITERAL: case LONGLITERAL: case FLOATLITERAL: case DOUBLELITERAL:
                    case NULL: case IDENTIFIER: case UNDERSCORE: case TRUE: case FALSE:
                    case NEW: case SWITCH: case THIS: case SUPER:
                    case BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE, VOID, BOOLEAN:
                        isYieldStatement = true;
                        break;
                    case PLUSPLUS: case SUBSUB:
                        isYieldStatement = S.token(2).kind != SEMI;
                        break;
                    case BANG: case TILDE:
                        isYieldStatement = S.token(1).kind != SEMI;
                        break;
                    case LPAREN:
                        int lookahead = 2;
                        int balance = 1;
                        boolean hasComma = false;
                        boolean inTypeArgs = false;
                        Token l;
                        while ((l = S.token(lookahead)).kind != EOF && balance != 0) {
                            switch (l.kind) {
                                case LPAREN: balance++; break;
                                case RPAREN: balance--; break;
                                case COMMA: if (balance == 1 && !inTypeArgs) hasComma = true; break;
                                case LT: inTypeArgs = true; break;
                                case GT: inTypeArgs = false;
                            }
                            lookahead++;
                        }
                        isYieldStatement = (!hasComma && lookahead != 3) || l.kind == ARROW;
                        break;
                    case SEMI: //error recovery - this is not a valid statement:
                        isYieldStatement = true;
                        break;
                    default:
                        isYieldStatement = false;
                        break;
                }

                if (isYieldStatement) {
                    nextToken();
                    JCExpression t = term(EXPR);
                    accept(SEMI);
                    return List.of(toP(F.at(pos).Yield(t)));
                }

                //else intentional fall-through
            } else {
                if (isNonSealedClassStart(true)) {
                    log.error(token.pos, Errors.SealedOrNonSealedLocalClassesNotAllowed);
                    nextToken();
                    nextToken();
                    nextToken();
                    return List.of(classOrRecordOrInterfaceOrEnumDeclaration(modifiersOpt(), token.docComment()));
                } else if (isSealedClassStart(true)) {
                    checkSourceLevel(Feature.SEALED_CLASSES);
                    log.error(token.pos, Errors.SealedOrNonSealedLocalClassesNotAllowed);
                    nextToken();
                    return List.of(classOrRecordOrInterfaceOrEnumDeclaration(modifiersOpt(), token.docComment()));
                }
            }
        }
        dc = token.docComment();
        if (isRecordStart() && allowRecords) {
            return List.of(recordDeclaration(F.at(pos).Modifiers(0), dc));
        } else {
            Token prevToken = token;
            JCExpression t = term(EXPR | TYPE);
            if (token.kind == COLON && t.hasTag(IDENT)) {
                nextToken();
                JCStatement stat = parseStatementAsBlock();
                return List.of(F.at(pos).Labelled(prevToken.name(), stat));
            } else if (wasTypeMode() && LAX_IDENTIFIER.test(token.kind)) {
                pos = token.pos;
                JCModifiers mods = F.at(Position.NOPOS).Modifiers(0);
                F.at(pos);
                return localVariableDeclarations(mods, t, dc);
            } else {
                // This Exec is an "ExpressionStatement"; it subsumes the terminating semicolon
                t = checkExprStat(t);
                accept(SEMI);
                JCExpressionStatement expr = toP(F.at(pos).Exec(t));
                return List.of(expr);
            }
        }
    }
    //where
        private List<JCStatement> localVariableDeclarations(JCModifiers mods, JCExpression type, Comment dc) {
            if (dc != null) {
                // ignore a well-placed doc comment, but save any misplaced ones
                saveDanglingDocComments(dc);
            }
            ListBuffer<JCStatement> stats =
                    variableDeclarators(mods, type, new ListBuffer<>(), true);
            // A "LocalVariableDeclarationStatement" subsumes the terminating semicolon
            accept(SEMI);
            storeEnd(stats.last(), S.prevToken().endPos);
            return stats.toList();
        }

    /** Statement =
     *       Block
     *     | IF ParExpression Statement [ELSE Statement]
     *     | FOR "(" ForInitOpt ";" [Expression] ";" ForUpdateOpt ")" Statement
     *     | FOR "(" FormalParameter : Expression ")" Statement
     *     | WHILE ParExpression Statement
     *     | DO Statement WHILE ParExpression ";"
     *     | TRY Block ( Catches | [Catches] FinallyPart )
     *     | TRY "(" ResourceSpecification ";"opt ")" Block [Catches] [FinallyPart]
     *     | SWITCH ParExpression "{" SwitchBlockStatementGroups "}"
     *     | SYNCHRONIZED ParExpression Block
     *     | RETURN [Expression] ";"
     *     | THROW Expression ";"
     *     | BREAK [Ident] ";"
     *     | CONTINUE [Ident] ";"
     *     | ASSERT Expression [ ":" Expression ] ";"
     *     | ";"
     */
    public JCStatement parseSimpleStatement() {
        ignoreDanglingComments(); // ignore comments before statement
        int pos = token.pos;
        switch (token.kind) {
        case LBRACE:
            return block();
        case IF: {
            nextToken();
            JCExpression cond = parExpression();
            JCStatement thenpart = parseStatementAsBlock();
            JCStatement elsepart = null;
            if (token.kind == ELSE) {
                nextToken();
                elsepart = parseStatementAsBlock();
            }
            return F.at(pos).If(cond, thenpart, elsepart);
        }
        case FOR: {
            nextToken();
            accept(LPAREN);
            List<JCStatement> inits = token.kind == SEMI ? List.nil() : forInit();
            if (inits.length() == 1 &&
                inits.head.hasTag(VARDEF) &&
                ((JCVariableDecl) inits.head).init == null &&
                token.kind == COLON) {
                JCVariableDecl var = (JCVariableDecl)inits.head;
                accept(COLON);
                JCExpression expr = parseExpression();
                accept(RPAREN);
                JCStatement body = parseStatementAsBlock();
                return F.at(pos).ForeachLoop(var, expr, body);
            } else {
                accept(SEMI);
                JCExpression cond = token.kind == SEMI ? null : parseExpression();
                accept(SEMI);
                List<JCExpressionStatement> steps = token.kind == RPAREN ? List.nil() : forUpdate();
                accept(RPAREN);
                JCStatement body = parseStatementAsBlock();
                return F.at(pos).ForLoop(inits, cond, steps, body);
            }
        }
        case WHILE: {
            nextToken();
            JCExpression cond = parExpression();
            JCStatement body = parseStatementAsBlock();
            return F.at(pos).WhileLoop(cond, body);
        }
        case DO: {
            nextToken();
            JCStatement body = parseStatementAsBlock();
            accept(WHILE);
            JCExpression cond = parExpression();
            accept(SEMI);
            JCDoWhileLoop t = toP(F.at(pos).DoLoop(body, cond));
            return t;
        }
        case TRY: {
            nextToken();
            List<JCTree> resources = List.nil();
            if (token.kind == LPAREN) {
                nextToken();
                resources = resources();
                accept(RPAREN);
            }
            JCBlock body = block();
            ListBuffer<JCCatch> catchers = new ListBuffer<>();
            JCBlock finalizer = null;
            if (token.kind == CATCH || token.kind == FINALLY) {
                while (token.kind == CATCH) catchers.append(catchClause());
                if (token.kind == FINALLY) {
                    nextToken();
                    finalizer = block();
                }
            } else {
                if (resources.isEmpty()) {
                    log.error(DiagnosticFlag.SYNTAX, pos, Errors.TryWithoutCatchFinallyOrResourceDecls);
                }
            }
            return F.at(pos).Try(resources, body, catchers.toList(), finalizer);
        }
        case SWITCH: {
            nextToken();
            JCExpression selector = parExpression();
            accept(LBRACE);
            List<JCCase> cases = switchBlockStatementGroups();
            JCSwitch t = to(F.at(pos).Switch(selector, cases));
            t.bracePos = token.endPos;
            accept(RBRACE);
            return t;
        }
        case SYNCHRONIZED: {
            nextToken();
            JCExpression lock = parExpression();
            JCBlock body = block();
            return F.at(pos).Synchronized(lock, body);
        }
        case RETURN: {
            nextToken();
            JCExpression result = token.kind == SEMI ? null : parseExpression();
            accept(SEMI);
            JCReturn t = toP(F.at(pos).Return(result));
            return t;
        }
        case FASTRETURN: {
            nextToken();
            JCExpression result = token.kind == SEMI ? null : parseExpression();
            accept(SEMI);
            JCFastReturn t = toP(F.at(pos).FastReturn(result));
            return t;
        }
        case THROW: {
            nextToken();
            JCExpression exc = parseExpression();
            accept(SEMI);
            JCThrow t = toP(F.at(pos).Throw(exc));
            return t;
        }
        case BREAK: {
            nextToken();
            Name label = LAX_IDENTIFIER.test(token.kind) ? ident() : null;
            accept(SEMI);
            JCBreak t = toP(F.at(pos).Break(label));
            return t;
        }
        case CONTINUE: {
            nextToken();
            Name label = LAX_IDENTIFIER.test(token.kind) ? ident() : null;
            accept(SEMI);
            JCContinue t =  toP(F.at(pos).Continue(label));
            return t;
        }
        case SEMI:
            nextToken();
            return toP(F.at(pos).Skip());
        case ELSE:
            int elsePos = token.pos;
            nextToken();
            return doRecover(elsePos, BasicErrorRecoveryAction.BLOCK_STMT, Errors.ElseWithoutIf);
        case FINALLY:
            int finallyPos = token.pos;
            nextToken();
            return doRecover(finallyPos, BasicErrorRecoveryAction.BLOCK_STMT, Errors.FinallyWithoutTry);
        case CATCH:
            return doRecover(token.pos, BasicErrorRecoveryAction.CATCH_CLAUSE, Errors.CatchWithoutTry);
        case ASSERT: {
            nextToken();
            JCExpression assertion = parseExpression();
            JCExpression message = null;
            if (token.kind == COLON) {
                nextToken();
                message = parseExpression();
            }
            accept(SEMI);
            JCAssert t = toP(F.at(pos).Assert(assertion, message));
            return t;
        }
        default:
            Assert.error();
            return null;
        }
    }

    @Override
    public JCStatement parseStatement() {
        return parseStatementAsBlock();
    }

    private JCStatement doRecover(int startPos, ErrorRecoveryAction action, Error errorKey) {
        int errPos = S.errPos();
        JCTree stm = action.doRecover(this);
        S.errPos(errPos);
        return toP(F.Exec(syntaxError(startPos, List.of(stm), errorKey)));
    }

    /** CatchClause     = CATCH "(" FormalParameter ")" Block
     * TODO: the "FormalParameter" is not correct, it uses the special "catchTypes" rule below.
     */
    protected JCCatch catchClause() {
        int pos = token.pos;
        accept(CATCH);
        accept(LPAREN);
        JCModifiers mods = optFinal(Flags.PARAMETER);
        List<JCExpression> catchTypes = catchTypes();
        JCExpression paramType = catchTypes.size() > 1 ?
                toP(F.at(catchTypes.head.getStartPosition()).TypeUnion(catchTypes)) :
                catchTypes.head;
        JCVariableDecl formal = variableDeclaratorId(mods, paramType, true, false, false);
        accept(RPAREN);
        JCBlock body = block();
        return F.at(pos).Catch(formal, body);
    }

    List<JCExpression> catchTypes() {
        ListBuffer<JCExpression> catchTypes = new ListBuffer<>();
        catchTypes.add(parseType());
        while (token.kind == BAR) {
            nextToken();
            // Instead of qualident this is now parseType.
            // But would that allow too much, e.g. arrays or generics?
            catchTypes.add(parseType());
        }
        return catchTypes.toList();
    }

    /** SwitchBlockStatementGroups = { SwitchBlockStatementGroup }
     *  SwitchBlockStatementGroup = SwitchLabel BlockStatements
     *  SwitchLabel = CASE ConstantExpression ":" | DEFAULT ":"
     */
    List<JCCase> switchBlockStatementGroups() {
        ListBuffer<JCCase> cases = new ListBuffer<>();
        while (true) {
            int pos = token.pos;
            switch (token.kind) {
            case CASE:
            case DEFAULT:
                cases.appendList(switchBlockStatementGroup());
                break;
            case RBRACE: case EOF:
                return cases.toList();
            default:
                nextToken(); // to ensure progress
                syntaxError(pos, Errors.Expected3(CASE, DEFAULT, RBRACE));
            }
        }
    }

    protected List<JCCase> switchBlockStatementGroup() {
        int pos = token.pos;
        List<JCStatement> stats;
        JCCase c;
        ListBuffer<JCCase> cases = new ListBuffer<JCCase>();
        switch (token.kind) {
        case CASE: {
            nextToken();
            ListBuffer<JCCaseLabel> pats = new ListBuffer<>();
            boolean allowDefault = false;
            while (true) {
                JCCaseLabel label = parseCaseLabel(allowDefault);
                pats.append(label);
                if (token.kind != COMMA) break;
                nextToken();
                checkSourceLevel(Feature.SWITCH_MULTIPLE_CASE_LABELS);
                allowDefault = TreeInfo.isNullCaseLabel(label);
            };
            JCExpression guard = parseGuard(pats.last());
            CaseTree.CaseKind caseKind;
            JCTree body = null;
            if (token.kind == ARROW) {
                checkSourceLevel(Feature.SWITCH_RULE);
                accept(ARROW);
                caseKind = JCCase.RULE;
                JCStatement statement = parseStatementAsBlock();
                if (!statement.hasTag(EXEC) && !statement.hasTag(BLOCK) && !statement.hasTag(Tag.THROW)) {
                    log.error(statement.pos(), Errors.SwitchCaseUnexpectedStatement);
                }
                stats = List.of(statement);
                body = stats.head;
            } else {
                accept(COLON, tk -> Errors.Expected2(COLON, ARROW));
                caseKind = JCCase.STATEMENT;
                stats = blockStatements();
            }
            c = F.at(pos).Case(caseKind, pats.toList(), guard, stats, body);
            if (stats.isEmpty())
                storeEnd(c, S.prevToken().endPos);
            return cases.append(c).toList();
        }
        case DEFAULT: {
            nextToken();
            JCCaseLabel defaultPattern = toP(F.at(pos).DefaultCaseLabel());
            JCExpression guard = parseGuard(defaultPattern);
            CaseTree.CaseKind caseKind;
            JCTree body = null;
            if (token.kind == ARROW) {
                checkSourceLevel(Feature.SWITCH_RULE);
                accept(ARROW);
                caseKind = JCCase.RULE;
                JCStatement statement = parseStatementAsBlock();
                if (!statement.hasTag(EXEC) && !statement.hasTag(BLOCK) && !statement.hasTag(Tag.THROW)) {
                    log.error(statement.pos(), Errors.SwitchCaseUnexpectedStatement);
                }
                stats = List.of(statement);
                body = stats.head;
            } else {
                accept(COLON, tk -> Errors.Expected2(COLON, ARROW));
                caseKind = JCCase.STATEMENT;
                stats = blockStatements();
            }
            c = F.at(pos).Case(caseKind, List.of(defaultPattern), guard, stats, body);
            if (stats.isEmpty())
                storeEnd(c, S.prevToken().endPos);
            return cases.append(c).toList();
        }
        }
        throw new AssertionError("should not reach here");
    }

    private JCCaseLabel parseCaseLabel(boolean allowDefault) {
        int patternPos = token.pos;
        JCCaseLabel label;

        if (token.kind == DEFAULT) {
            checkSourceLevel(token.pos, Feature.PATTERN_SWITCH);
            if (!allowDefault) {
                reportSyntaxError(new SimpleDiagnosticPosition(token.pos),
                                  Errors.DefaultLabelNotAllowed);
            }
            nextToken();
            label = toP(F.at(patternPos).DefaultCaseLabel());
        } else {
            JCModifiers mods = optFinal(0);
            boolean pattern = mods.flags != 0 || mods.annotations.nonEmpty() ||
                              analyzePattern(0) == PatternResult.PATTERN;
            if (pattern) {
                checkSourceLevel(token.pos, Feature.PATTERN_SWITCH);
                JCPattern p = parsePattern(patternPos, mods, null, false, true);
                return toP(F.at(patternPos).PatternCaseLabel(p));
            } else {
                JCExpression expr = term(EXPR | NOLAMBDA);
                return toP(F.at(patternPos).ConstantCaseLabel(expr));
            }
        }

        return label;
    }

    private JCExpression parseGuard(JCCaseLabel label) {
        JCExpression guard = null;

        if (token.kind == IDENTIFIER && token.name() == names.when) {
            int pos = token.pos;

            nextToken();
            guard = term(EXPR | NOLAMBDA);

            if (!(label instanceof JCPatternCaseLabel)) {
                guard = syntaxError(pos, List.of(guard), Errors.GuardNotAllowed);
            }
        }

        return guard;
    }
    @SuppressWarnings("fallthrough")
    PatternResult analyzePattern(int lookahead) {
        int typeDepth = 0;
        int parenDepth = 0;
        PatternResult pendingResult = PatternResult.EXPRESSION;
        while (true) {
            TokenKind token = S.token(lookahead).kind;
            switch (token) {
                case BYTE: case SHORT: case INT: case LONG: case FLOAT:
                case DOUBLE: case BOOLEAN: case CHAR: case VOID:
                case ASSERT, ENUM, IDENTIFIER:
                    if (typeDepth == 0 && peekToken(lookahead, LAX_IDENTIFIER)) {
                        if (parenDepth == 0) {
                            return PatternResult.PATTERN;
                        } else {
                            pendingResult = PatternResult.PATTERN;
                        }
                    } else if (typeDepth == 0 && parenDepth == 0 && (peekToken(lookahead, tk -> tk == ARROW || tk == COMMA))) {
                        return PatternResult.EXPRESSION;
                    }
                    break;
                case UNDERSCORE:
                    // TODO: REFACTOR to remove the code duplication
                    if (typeDepth == 0 && peekToken(lookahead, tk -> tk == RPAREN || tk == COMMA)) {
                        return PatternResult.PATTERN;
                    } else if (typeDepth == 0 && peekToken(lookahead, LAX_IDENTIFIER)) {
                        if (parenDepth == 0) {
                            return PatternResult.PATTERN;
                        } else {
                            pendingResult = PatternResult.PATTERN;
                        }
                    }
                    break;
                case DOT, QUES, EXTENDS, SUPER, COMMA: break;
                case LT: typeDepth++; break;
                case GTGTGT: typeDepth--;
                case GTGT: typeDepth--;
                case GT:
                    typeDepth--;
                    if (typeDepth == 0 && !peekToken(lookahead, DOT)) {
                         return peekToken(lookahead, LAX_IDENTIFIER) ||
                                peekToken(lookahead, tk -> tk == LPAREN) ? PatternResult.PATTERN
                                                                         : PatternResult.EXPRESSION;
                    } else if (typeDepth < 0) return PatternResult.EXPRESSION;
                    break;
                case MONKEYS_AT:
                    lookahead = skipAnnotation(lookahead);
                    break;
                case LBRACKET:
                    if (peekToken(lookahead, RBRACKET, LAX_IDENTIFIER)) {
                        return PatternResult.PATTERN;
                    } else if (peekToken(lookahead, RBRACKET)) {
                        lookahead++;
                        break;
                    } else {
                        // This is a potential guard, if we are already in a pattern
                        return pendingResult;
                    }
                case LPAREN:
                    if (S.token(lookahead + 1).kind == RPAREN) {
                        return parenDepth != 0 && S.token(lookahead + 2).kind == ARROW
                                ? PatternResult.EXPRESSION
                                : PatternResult.PATTERN;
                    }
                    parenDepth++; break;
                case RPAREN:
                    parenDepth--;
                    if (parenDepth == 0 &&
                        typeDepth == 0 &&
                        peekToken(lookahead, TokenKind.IDENTIFIER) &&
                        S.token(lookahead + 1).name() == names.when) {
                        return PatternResult.PATTERN;
                    }
                    break;
                case ARROW: return parenDepth > 0 ? PatternResult.EXPRESSION
                                                   : pendingResult;
                case FINAL:
                    if (parenDepth > 0) return PatternResult.PATTERN;
                default: return pendingResult;
            }
            lookahead++;
        }
    }

    private enum PatternResult {
        EXPRESSION,
        PATTERN;
    }

    /** MoreStatementExpressions = { COMMA StatementExpression }
     */
    <T extends ListBuffer<? super JCExpressionStatement>> T moreStatementExpressions(int pos,
                                                                    JCExpression first,
                                                                    T stats) {
        // This Exec is a "StatementExpression"; it subsumes no terminating token
        stats.append(toP(F.at(pos).Exec(checkExprStat(first))));
        while (token.kind == COMMA) {
            nextToken();
            pos = token.pos;
            JCExpression t = parseExpression();
            // This Exec is a "StatementExpression"; it subsumes no terminating token
            stats.append(toP(F.at(pos).Exec(checkExprStat(t))));
        }
        return stats;
    }

    /** ForInit = StatementExpression MoreStatementExpressions
     *           |  { FINAL | '@' Annotation } Type VariableDeclarators
     */
    List<JCStatement> forInit() {
        ListBuffer<JCStatement> stats = new ListBuffer<>();
        int pos = token.pos;
        if (token.kind == FINAL || token.kind == MONKEYS_AT) {
            return variableDeclarators(optFinal(0), parseType(true), stats, true).toList();
        } else {
            JCExpression t = term(EXPR | TYPE);
            if (wasTypeMode() && LAX_IDENTIFIER.test(token.kind)) {
                return variableDeclarators(modifiersOpt(), t, stats, true).toList();
            } else if (wasTypeMode() && token.kind == COLON) {
                log.error(DiagnosticFlag.SYNTAX, pos, Errors.BadInitializer("for-loop"));
                return List.of((JCStatement)F.at(pos).VarDef(modifiersOpt(), names.error, t, null));
            } else {
                return moreStatementExpressions(pos, t, stats).toList();
            }
        }
    }

    /** ForUpdate = StatementExpression MoreStatementExpressions
     */
    List<JCExpressionStatement> forUpdate() {
        return moreStatementExpressions(token.pos,
                                        parseExpression(),
                                        new ListBuffer<JCExpressionStatement>()).toList();
    }

    /** AnnotationsOpt = { '@' Annotation }
     *
     * @param kind Whether to parse an ANNOTATION or TYPE_ANNOTATION
     */
    protected List<JCAnnotation> annotationsOpt(Tag kind) {
        if (token.kind != MONKEYS_AT) return List.nil(); // optimization
        ListBuffer<JCAnnotation> buf = new ListBuffer<>();
        int prevmode = mode;
        while (token.kind == MONKEYS_AT) {
            int pos = token.pos;
            nextToken();
            buf.append(annotation(pos, kind));
        }
        setLastMode(mode);
        setMode(prevmode);
        List<JCAnnotation> annotations = buf.toList();

        return annotations;
    }

    List<JCAnnotation> typeAnnotationsOpt() {
        List<JCAnnotation> annotations = annotationsOpt(Tag.TYPE_ANNOTATION);
        return annotations;
    }

    /** ModifiersOpt = { Modifier }
     *  Modifier = PUBLIC | PROTECTED | PRIVATE | STATIC | ABSTRACT | FINAL
     *           | NATIVE | SYNCHRONIZED | TRANSIENT | VOLATILE | "@"
     *           | "@" Annotation
     */
    protected JCModifiers modifiersOpt() {
        return modifiersOpt(null);
    }
    protected JCModifiers modifiersOpt(JCModifiers partial) {
        long flags;
        ListBuffer<JCAnnotation> annotations = new ListBuffer<>();
        int pos;
        if (partial == null) {
            flags = 0;
            pos = token.pos;
        } else {
            flags = partial.flags;
            annotations.appendList(partial.annotations);
            pos = partial.pos;
        }
        if (token.deprecatedFlag()) {
            flags |= Flags.DEPRECATED;
        }
        int lastPos;
    loop:
        while (true) {
            long flag;
            switch (token.kind) {
            case PRIVATE     : flag = Flags.PRIVATE; break;
            case PROTECTED   : flag = Flags.PROTECTED; break;
            case PUBLIC      : flag = Flags.PUBLIC; break;
            case STATIC      : flag = Flags.STATIC; break;
            case TRANSIENT   : flag = Flags.TRANSIENT; break;
            case FINAL       : flag = Flags.FINAL; break;
            case ABSTRACT    : flag = Flags.ABSTRACT; break;
            case NATIVE      : flag = Flags.NATIVE; break;
            case VOLATILE    : flag = Flags.VOLATILE; break;
            case SYNCHRONIZED: flag = Flags.SYNCHRONIZED; break;
            case STRICTFP    : flag = Flags.STRICTFP; break;
            case MONKEYS_AT  : flag = Flags.ANNOTATION; break;
            case DEFAULT     : flag = Flags.DEFAULT; break;
            case ERROR       : flag = 0; nextToken(); break;
            case IDENTIFIER  : {
                if (isNonSealedClassStart(false)) {
                    flag = Flags.NON_SEALED;
                    nextToken();
                    nextToken();
                    break;
                }
                if (isSealedClassStart(false)) {
                    checkSourceLevel(Feature.SEALED_CLASSES);
                    flag = Flags.SEALED;
                    break;
                }
                break loop;
            }
            default: break loop;
            }
            if ((flags & flag) != 0) log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.RepeatedModifier);
            lastPos = token.pos;
            nextToken();
            if (flag == Flags.ANNOTATION) {
                if (token.kind != INTERFACE) {
                    JCAnnotation ann = annotation(lastPos, Tag.ANNOTATION);
                    // if first modifier is an annotation, set pos to annotation's.
                    if (flags == 0 && annotations.isEmpty())
                        pos = ann.pos;
                    annotations.append(ann);
                    flag = 0;
                }
            }
            flags |= flag;
        }
        switch (token.kind) {
        case ENUM: flags |= Flags.ENUM; break;
        case INTERFACE: flags |= Flags.INTERFACE; break;
        default: break;
        }

        /* A modifiers tree with no modifier tokens or annotations
         * has no text position. */
        if ((flags & (Flags.ModifierFlags | Flags.ANNOTATION)) == 0 && annotations.isEmpty())
            pos = Position.NOPOS;

        JCModifiers mods = F.at(pos).Modifiers(flags, annotations.toList());
        if (pos != Position.NOPOS)
            storeEnd(mods, S.prevToken().endPos);
        return mods;
    }

    /** Annotation              = "@" Qualident [ "(" AnnotationFieldValues ")" ]
     *
     * @param pos position of "@" token
     * @param kind Whether to parse an ANNOTATION or TYPE_ANNOTATION
     */
    JCAnnotation annotation(int pos, Tag kind) {
        // accept(AT); // AT consumed by caller
        JCTree ident = qualident(false);
        List<JCExpression> fieldValues = annotationFieldValuesOpt();
        JCAnnotation ann;
        if (kind == Tag.ANNOTATION) {
            ann = F.at(pos).Annotation(ident, fieldValues);
        } else if (kind == Tag.TYPE_ANNOTATION) {
            ann = F.at(pos).TypeAnnotation(ident, fieldValues);
        } else {
            throw new AssertionError("Unhandled annotation kind: " + kind);
        }
        return toP(ann);
    }

    List<JCExpression> annotationFieldValuesOpt() {
        return (token.kind == LPAREN) ? annotationFieldValues() : List.nil();
    }

    /** AnnotationFieldValues   = "(" [ AnnotationFieldValue { "," AnnotationFieldValue } ] ")" */
    List<JCExpression> annotationFieldValues() {
        accept(LPAREN);
        ListBuffer<JCExpression> buf = new ListBuffer<>();
        if (token.kind != RPAREN) {
            buf.append(annotationFieldValue());
            while (token.kind == COMMA) {
                nextToken();
                buf.append(annotationFieldValue());
            }
        }
        accept(RPAREN);
        return buf.toList();
    }

    /** AnnotationFieldValue    = AnnotationValue
     *                          | Identifier "=" AnnotationValue
     */
    JCExpression annotationFieldValue() {
        if (LAX_IDENTIFIER.test(token.kind)) {
            selectExprMode();
            JCExpression t1 = term1();
            if (t1.hasTag(IDENT) && token.kind == EQ) {
                int pos = token.pos;
                accept(EQ);
                JCExpression v = annotationValue();
                return toP(F.at(pos).Assign(t1, v));
            } else {
                return t1;
            }
        }
        return annotationValue();
    }

    /* AnnotationValue          = ConditionalExpression
     *                          | Annotation
     *                          | "{" [ AnnotationValue { "," AnnotationValue } ] [","] "}"
     */
    JCExpression annotationValue() {
        int pos;
        switch (token.kind) {
        case MONKEYS_AT:
            pos = token.pos;
            nextToken();
            return annotation(pos, Tag.ANNOTATION);
        case LBRACE:
            pos = token.pos;
            accept(LBRACE);
            ListBuffer<JCExpression> buf = new ListBuffer<>();
            if (token.kind == COMMA) {
                nextToken();
            } else if (token.kind != RBRACE) {
                buf.append(annotationValue());
                while (token.kind == COMMA) {
                    nextToken();
                    if (token.kind == RBRACE) break;
                    buf.append(annotationValue());
                }
            }
            accept(RBRACE, tk -> Errors.AnnotationMissingElementValue);
            return toP(F.at(pos).NewArray(null, List.nil(), buf.toList()));
        default:
            selectExprMode();
            return term1();
        }
    }

    /** VariableDeclarators = VariableDeclarator { "," VariableDeclarator }
     */
    public <T extends ListBuffer<? super JCVariableDecl>> T variableDeclarators(JCModifiers mods,
                                                                         JCExpression type,
                                                                         T vdefs,
                                                                         boolean localDecl)
    {
        return variableDeclaratorsRest(token.pos, mods, type, identOrUnderscore(), false, null, vdefs, localDecl);
    }

    /** VariableDeclaratorsRest = VariableDeclaratorRest { "," VariableDeclarator }
     *  ConstantDeclaratorsRest = ConstantDeclaratorRest { "," ConstantDeclarator }
     *
     *  @param reqInit  Is an initializer always required?
     *  @param dc       The documentation comment for the variable declarations, or null.
     */
    protected <T extends ListBuffer<? super JCVariableDecl>> T variableDeclaratorsRest(int pos,
                                                                     JCModifiers mods,
                                                                     JCExpression type,
                                                                     Name name,
                                                                     boolean reqInit,
                                                                     Comment dc,
                                                                     T vdefs,
                                                                     boolean localDecl)
    {
        JCVariableDecl head = variableDeclaratorRest(pos, mods, type, name, reqInit, dc, localDecl, false);
        vdefs.append(head);
        while (token.kind == COMMA) {
            // All but last of multiple declarators subsume a comma
            storeEnd((JCTree)vdefs.last(), token.endPos);
            nextToken();
            vdefs.append(variableDeclarator(mods, type, reqInit, dc, localDecl));
        }
        return vdefs;
    }

    /** VariableDeclarator = Ident VariableDeclaratorRest
     *  ConstantDeclarator = Ident ConstantDeclaratorRest
     */
    JCVariableDecl variableDeclarator(JCModifiers mods, JCExpression type, boolean reqInit, Comment dc, boolean localDecl) {
        return variableDeclaratorRest(token.pos, mods, type, identOrUnderscore(), reqInit, dc, localDecl, true);
    }

    /** VariableDeclaratorRest = BracketsOpt ["=" VariableInitializer]
     *  ConstantDeclaratorRest = BracketsOpt "=" VariableInitializer
     *
     *  @param reqInit  Is an initializer always required?
     *  @param dc       The documentation comment for the variable declarations, or null.
     */
    JCVariableDecl variableDeclaratorRest(int pos, JCModifiers mods, JCExpression type, Name name,
                                  boolean reqInit, Comment dc, boolean localDecl, boolean compound) {
        boolean declaredUsingVar = false;
        JCExpression init = null;
        type = bracketsOpt(type);

        if (Feature.UNNAMED_VARIABLES.allowedInSource(source) && name == names.underscore) {
            if (!localDecl) {
                log.error(DiagnosticFlag.SYNTAX, pos, Errors.UseOfUnderscoreNotAllowed);
            }
            name = names.empty;
        }

        saveDanglingDocComments(dc);

        if (token.kind == EQ) {
            nextToken();
            init = variableInitializer();
        }
        else if (reqInit) syntaxError(token.pos, Errors.Expected(EQ));

        if (Feature.UNNAMED_VARIABLES.allowedInSource(source) && name == names.empty
                && localDecl
                && init == null
                && token.kind != COLON) { // if its unnamed local variable, it needs to have an init unless in enhanced-for
            syntaxError(token.pos, Errors.Expected(EQ));
        }

        int startPos = Position.NOPOS;
        JCTree elemType = TreeInfo.innermostType(type, true);
        if (elemType.hasTag(IDENT)) {
            Name typeName = ((JCIdent) elemType).name;
            if (restrictedTypeNameStartingAtSource(typeName, pos, !compound && localDecl) != null) {
                if (typeName != names.var) {
                    reportSyntaxError(elemType.pos, Errors.RestrictedTypeNotAllowedHere(typeName));
                } else if (type.hasTag(TYPEARRAY) && !compound) {
                    //error - 'var' and arrays
                    reportSyntaxError(elemType.pos, Errors.RestrictedTypeNotAllowedArray(typeName));
                } else {
                    declaredUsingVar = true;
                    if (compound)
                        //error - 'var' in compound local var decl
                        reportSyntaxError(elemType.pos, Errors.RestrictedTypeNotAllowedCompound(typeName));
                    startPos = TreeInfo.getStartPos(mods);
                    if (startPos == Position.NOPOS)
                        startPos = TreeInfo.getStartPos(type);
                    //implicit type
                    type = null;
                }
            }
        }
        JCVariableDecl result = toP(F.at(pos).VarDef(mods, name, type, init, declaredUsingVar));
        result.startPos = startPos;
        return attach(result, dc);
    }

    Name restrictedTypeName(JCExpression e, boolean shouldWarn) {
        switch (e.getTag()) {
            case IDENT:
                return restrictedTypeNameStartingAtSource(((JCIdent)e).name, e.pos, shouldWarn) != null ? ((JCIdent)e).name : null;
            case TYPEARRAY:
                return restrictedTypeName(((JCArrayTypeTree)e).elemtype, shouldWarn);
            default:
                return null;
        }
    }

    Source restrictedTypeNameStartingAtSource(Name name, int pos, boolean shouldWarn) {
        if (name == names.var) {
            if (Feature.LOCAL_VARIABLE_TYPE_INFERENCE.allowedInSource(source)) {
                return Source.JDK10;
            } else if (shouldWarn) {
                log.warning(pos, Warnings.RestrictedTypeNotAllowed(name, Source.JDK10));
            }
        }
        if (name == names.yield) {
            if (allowYieldStatement) {
                return Source.JDK14;
            } else if (shouldWarn) {
                log.warning(pos, Warnings.RestrictedTypeNotAllowed(name, Source.JDK14));
            }
        }
        if (name == names.record) {
            if (allowRecords) {
                return Source.JDK14;
            } else if (shouldWarn) {
                log.warning(pos, Warnings.RestrictedTypeNotAllowedPreview(name, Source.JDK14));
            }
        }
        if (name == names.sealed) {
            if (allowSealedTypes) {
                return Source.JDK15;
            } else if (shouldWarn) {
                log.warning(pos, Warnings.RestrictedTypeNotAllowedPreview(name, Source.JDK15));
            }
        }
        if (name == names.permits) {
            if (allowSealedTypes) {
                return Source.JDK15;
            } else if (shouldWarn) {
                log.warning(pos, Warnings.RestrictedTypeNotAllowedPreview(name, Source.JDK15));
            }
        }
        return null;
    }

    /** VariableDeclaratorId = Ident BracketsOpt
     */
    JCVariableDecl variableDeclaratorId(JCModifiers mods, JCExpression type, boolean catchParameter, boolean lambdaParameter, boolean recordComponent) {
        int pos = token.pos;
        Name name;
        if (allowThisIdent ||
            !lambdaParameter ||
            LAX_IDENTIFIER.test(token.kind) ||
            mods.flags != Flags.PARAMETER ||
            mods.annotations.nonEmpty()) {
            JCExpression pn;
            if (token.kind == UNDERSCORE && (catchParameter || lambdaParameter)) {
                pn = toP(F.at(token.pos).Ident(identOrUnderscore()));
            } else {
                pn = qualident(false);
            }
            if (pn.hasTag(Tag.IDENT) && ((JCIdent)pn).name != names._this) {
                name = ((JCIdent)pn).name;
            } else if (lambdaParameter && type == null) {
                // we have a lambda parameter that is not an identifier this is a syntax error
                type = pn;
                name = names.empty;
                reportSyntaxError(pos, Errors.Expected(IDENTIFIER));
            } else {
                if (allowThisIdent) {
                    if ((mods.flags & Flags.VARARGS) != 0) {
                        log.error(token.pos, Errors.VarargsAndReceiver);
                    }
                    if (token.kind == LBRACKET) {
                        log.error(token.pos, Errors.ArrayAndReceiver);
                    }
                    if (pn.hasTag(Tag.SELECT) && ((JCFieldAccess)pn).name != names._this) {
                        log.error(token.pos, Errors.WrongReceiver);
                    }
                }
                return toP(F.at(pos).ReceiverVarDef(mods, pn, type));
            }
        } else {
            /** if it is a lambda parameter and the token kind is not an identifier,
             *  and there are no modifiers or annotations, then this means that the compiler
             *  supposed the lambda to be explicit but it can contain a mix of implicit,
             *  var or explicit parameters. So we assign the error name to the parameter name
             *  instead of issuing an error and analyze the lambda parameters as a whole at
             *  a higher level.
             */
            name = names.error;
        }
        if ((mods.flags & Flags.VARARGS) != 0 &&
                token.kind == LBRACKET) {
            log.error(token.pos, Errors.VarargsAndOldArraySyntax);
        }
        if (recordComponent && token.kind == LBRACKET) {
            log.error(token.pos, Errors.RecordComponentAndOldArraySyntax);
        }
        type = bracketsOpt(type);

        if (Feature.UNNAMED_VARIABLES.allowedInSource(source) && name == names.underscore) {
            name = names.empty;
        }

        return toP(F.at(pos).VarDef(mods, name, type, null,
                type != null && type.hasTag(IDENT) && ((JCIdent)type).name == names.var));
    }

    /** Resources = Resource { ";" Resources }
     */
    List<JCTree> resources() {
        ListBuffer<JCTree> defs = new ListBuffer<>();
        defs.append(resource());
        while (token.kind == SEMI) {
            // All but last of multiple declarators must subsume a semicolon
            storeEnd(defs.last(), token.endPos);
            int semiColonPos = token.pos;
            nextToken();
            if (token.kind == RPAREN) { // Optional trailing semicolon
                                       // after last resource
                break;
            }
            defs.append(resource());
        }
        return defs.toList();
    }

    /** Resource = VariableModifiersOpt Type VariableDeclaratorId "=" Expression
     *           | Expression
     */
    protected JCTree resource() {
        if (token.kind == FINAL || token.kind == MONKEYS_AT) {
            JCModifiers mods = optFinal(0);
            JCExpression t = parseType(true);
            return variableDeclaratorRest(token.pos, mods, t, identOrUnderscore(), true, null, true, false);
        }
        JCExpression t = term(EXPR | TYPE);
        if (wasTypeMode() && LAX_IDENTIFIER.test(token.kind)) {
            JCModifiers mods = F.Modifiers(0);
            return variableDeclaratorRest(token.pos, mods, t, identOrUnderscore(), true, null, true, false);
        } else {
            checkSourceLevel(Feature.EFFECTIVELY_FINAL_VARIABLES_IN_TRY_WITH_RESOURCES);
            if (!t.hasTag(IDENT) && !t.hasTag(SELECT)) {
                log.error(t.pos(), Errors.TryWithResourcesExprNeedsVar);
            }

            return t;
        }
    }

    /** CompilationUnit = [ { "@" Annotation } PACKAGE Qualident ";"] {ImportDeclaration} {TypeDeclaration}
     */
    public JCTree.JCCompilationUnit parseCompilationUnit() {
        Token firstToken = token;
        JCModifiers mods = null;
        boolean consumedToplevelDoc = false;
        boolean seenImport = false;
        boolean seenPackage = false;
        ListBuffer<JCTree> defs = new ListBuffer<>();
        if (token.kind == MONKEYS_AT)
            mods = modifiersOpt();

        if (token.kind == PACKAGE) {
            int packagePos = token.pos;
            List<JCAnnotation> annotations = List.nil();
            seenPackage = true;
            if (mods != null) {
                checkNoMods(mods.flags & ~Flags.DEPRECATED);
                annotations = mods.annotations;
                mods = null;
            }
            nextToken();
            JCExpression pid = qualident(false);
            accept(SEMI);
            JCPackageDecl pd = toP(F.at(packagePos).PackageDecl(annotations, pid));
            attach(pd, firstToken.docComment());
            consumedToplevelDoc = true;
            defs.append(pd);
            updateUnexpectedTopLevelDefinitionStartError(true);
        }

        boolean firstTypeDecl = true;   // have we seen a class, enum, or interface declaration yet?
        boolean isImplicitClass = false;
        OUTER: while (token.kind != EOF) {
            if (token.pos <= endPosTable.errorEndPos) {
                // error recovery
                skip(firstTypeDecl, false, false, false);
                if (token.kind == EOF)
                    break;
            }
            // JLS 7.3 doesn't allow extra semicolons after package or import declarations,
            // but here we try to provide a more helpful error message if we encounter any.
            // Do that by slurping in as many semicolons as possible, and then seeing what
            // comes after before deciding how best to handle them.
            ListBuffer<JCTree> semiList = new ListBuffer<>();
            while (firstTypeDecl && mods == null && token.kind == SEMI) {
                int pos = token.pos;
                nextToken();
                semiList.append(toP(F.at(pos).Skip()));
                if (token.kind == EOF)
                    break OUTER;
            }
            if (firstTypeDecl && mods == null && token.kind == IMPORT) {
                if (!semiList.isEmpty()) {
                    if (source.compareTo(Source.JDK21) >= 0)
                        reportSyntaxError(semiList.first().pos, Errors.ExtraneousSemicolon);
                    else
                        log.warning(semiList.first().pos, Warnings.ExtraneousSemicolon);
                }
                seenImport = true;
                defs.append(importDeclaration());
            } else {
                Comment docComment = token.docComment();
                if (firstTypeDecl && !seenImport && !seenPackage) {
                    docComment = firstToken.docComment();
                    consumedToplevelDoc = true;
                }
                if (mods != null || token.kind != SEMI)
                    mods = modifiersOpt(mods);
                if (firstTypeDecl && token.kind == IDENTIFIER) {
                    if (!semiList.isEmpty()) {
                        if (source.compareTo(Source.JDK21) >= 0)
                            reportSyntaxError(semiList.first().pos, Errors.ExtraneousSemicolon);
                        else
                            log.warning(semiList.first().pos, Warnings.ExtraneousSemicolon);
                    }
                    ModuleKind kind = ModuleKind.STRONG;
                    if (token.name() == names.open) {
                        kind = ModuleKind.OPEN;
                        nextToken();
                    }
                    if (token.kind == IDENTIFIER && token.name() == names.module) {
                        if (mods != null) {
                            checkNoMods(mods.flags & ~Flags.DEPRECATED);
                        }
                        defs.append(moduleDecl(mods, kind, docComment));
                        consumedToplevelDoc = true;
                        break;
                    } else if (kind != ModuleKind.STRONG) {
                        reportSyntaxError(token.pos, Errors.ExpectedModule);
                    }
                }

                defs.appendList(semiList.toList());
                boolean isTopLevelMethodOrField = false;

                // Due to a significant number of existing negative tests
                // this code speculatively tests to see if a top level method
                // or field can parse. If the method or field can parse then
                // it is parsed. Otherwise, parsing continues as though
                // implicitly declared classes did not exist and error reporting
                // is the same as in the past.
                if (!isDeclaration(true)) {
                    final JCModifiers finalMods = mods;
                    JavacParser speculative = new VirtualParser(this);
                    List<JCTree> speculativeResult =
                            speculative.topLevelMethodOrFieldDeclaration(finalMods, null);
                    if (speculativeResult.head.hasTag(METHODDEF) ||
                        speculativeResult.head.hasTag(VARDEF)) {
                        isTopLevelMethodOrField = true;
                    }
                }

                if (isTopLevelMethodOrField) {
                    checkSourceLevel(token.pos, Feature.IMPLICIT_CLASSES);
                    defs.appendList(topLevelMethodOrFieldDeclaration(mods, docComment));
                    isImplicitClass = true;
                } else if (isDefiniteStatementStartToken()) {
                    int startPos = token.pos;
                    List<JCStatement> statements = blockStatement();
                    defs.append(syntaxError(startPos,
                                            statements,
                                            Errors.StatementNotExpected,
                                            true));
                } else {
                    JCTree def = typeDeclaration(mods, docComment);
                    if (def instanceof JCExpressionStatement statement)
                        def = statement.expr;
                    defs.append(def);
                }

                mods = null;
                firstTypeDecl = false;
            }
        }
        List<JCTree> topLevelDefs = isImplicitClass ? constructImplicitClass(defs.toList(), S.prevToken().endPos) : defs.toList();
        JCTree.JCCompilationUnit toplevel = F.at(firstToken.pos).TopLevel(topLevelDefs);
        if (!consumedToplevelDoc)
            attach(toplevel, firstToken.docComment());
        if (defs.isEmpty())
            storeEnd(toplevel, S.prevToken().endPos);
        if (keepDocComments)
            toplevel.docComments = docComments;
        if (keepLineMap)
            toplevel.lineMap = S.getLineMap();
        toplevel.endPositions = this.endPosTable;
        return toplevel;
    }

    // Restructure top level to be an implicitly declared class.
    private List<JCTree> constructImplicitClass(List<JCTree> origDefs, int endPos) {
        ListBuffer<JCTree> topDefs = new ListBuffer<>();
        ListBuffer<JCTree> defs = new ListBuffer<>();

        for (JCTree def : origDefs) {
            if (def.hasTag(Tag.PACKAGEDEF)) {
                log.error(def.pos(), Errors.ImplicitClassShouldNotHavePackageDeclaration);
            } else if (def.hasTag(Tag.IMPORT) || def.hasTag(Tag.MODULEIMPORT)) {
                topDefs.append(def);
            } else if (!def.hasTag(Tag.SKIP)) {
                defs.append(def);
            }
        }

        int primaryPos = getStartPos(defs.first());
        String simplename = PathFileObject.getSimpleName(log.currentSourceFile());

        if (simplename.endsWith(".java")) {
            simplename = simplename.substring(0, simplename.length() - ".java".length());
        }
        if (!SourceVersion.isIdentifier(simplename) || SourceVersion.isKeyword(simplename)) {
            log.error(primaryPos, Errors.BadFileName(simplename));
        }

        Name name = names.fromString(simplename);
        JCModifiers implicitMods = F.at(Position.NOPOS)
                .Modifiers(Flags.FINAL|Flags.IMPLICIT_CLASS, List.nil());
        JCClassDecl implicit = F.at(primaryPos).ClassDef(
                implicitMods, name, List.nil(), null, List.nil(), List.nil(),
                defs.toList());
        storeEnd(implicit, endPos);
        topDefs.append(implicit);
        return topDefs.toList();
    }

    JCModuleDecl moduleDecl(JCModifiers mods, ModuleKind kind, Comment dc) {
        int pos = token.pos;
        checkSourceLevel(Feature.MODULES);

        nextToken();
        JCExpression name = qualident(false);
        List<JCDirective> directives = null;

        accept(LBRACE);
        directives = moduleDirectiveList();
        accept(RBRACE);
        int endPos = S.prevToken().endPos;
        accept(EOF);

        JCModuleDecl result = F.at(pos).ModuleDef(mods, kind, name, directives);
        storeEnd(result, endPos);
        return attach(result, dc);
    }

    List<JCDirective> moduleDirectiveList() {
        ListBuffer<JCDirective> defs = new ListBuffer<>();
        while (token.kind == IDENTIFIER) {
            int pos = token.pos;
            if (token.name() == names.requires) {
                nextToken();
                boolean isTransitive = false;
                boolean isStaticPhase = false;
            loop:
                while (true) {
                    switch (token.kind) {
                        case IDENTIFIER:
                            if (token.name() == names.transitive) {
                                Token t1 = S.token(1);
                                if (t1.kind == SEMI || t1.kind == DOT) {
                                    break loop;
                                }
                                if (isTransitive) {
                                    log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.RepeatedModifier);
                                }
                                isTransitive = true;
                                break;
                            } else {
                                break loop;
                            }
                        case STATIC:
                            if (isStaticPhase) {
                                log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.RepeatedModifier);
                            }
                            isStaticPhase = true;
                            break;
                        default:
                            break loop;
                    }
                    nextToken();
                }
                JCExpression moduleName = qualident(false);
                accept(SEMI);
                defs.append(toP(F.at(pos).Requires(isTransitive, isStaticPhase, moduleName)));
            } else if (token.name() == names.exports || token.name() == names.opens) {
                boolean exports = token.name() == names.exports;
                nextToken();
                JCExpression pkgName = qualident(false);
                List<JCExpression> moduleNames = null;
                if (token.kind == IDENTIFIER && token.name() == names.to) {
                    nextToken();
                    moduleNames = qualidentList(false);
                }
                accept(SEMI);
                JCDirective d;
                if (exports) {
                    d = F.at(pos).Exports(pkgName, moduleNames);
                } else {
                    d = F.at(pos).Opens(pkgName, moduleNames);
                }
                defs.append(toP(d));
            } else if (token.name() == names.provides) {
                nextToken();
                JCExpression serviceName = qualident(false);
                List<JCExpression> implNames;
                if (token.kind == IDENTIFIER && token.name() == names.with) {
                    nextToken();
                    implNames = qualidentList(false);
                } else {
                    log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.ExpectedStr("'" + names.with + "'"));
                    implNames = List.nil();
                }
                accept(SEMI);
                defs.append(toP(F.at(pos).Provides(serviceName, implNames)));
            } else if (token.name() == names.uses) {
                nextToken();
                JCExpression service = qualident(false);
                accept(SEMI);
                defs.append(toP(F.at(pos).Uses(service)));
            } else {
                setErrorEndPos(pos);
                reportSyntaxError(pos, Errors.InvalidModuleDirective);
                break;
            }
        }
        return defs.toList();
    }

    /** ImportDeclaration = IMPORT [ STATIC ] Ident { "." Ident } [ "." "*" ] ";"
     */
    protected JCTree importDeclaration() {
        int pos = token.pos;
        nextToken();
        boolean importStatic = false;
        if (token.kind == STATIC) {
            importStatic = true;
            nextToken();
        } else if (token.kind == IDENTIFIER && token.name() == names.module &&
                   peekToken(TokenKind.IDENTIFIER)) {
            checkSourceLevel(Feature.MODULE_IMPORTS);
            nextToken();
            JCExpression moduleName = qualident(false);
            accept(SEMI);
            return toP(F.at(pos).ModuleImport(moduleName));
        }
        JCExpression pid = toP(F.at(token.pos).Ident(ident()));
        do {
            int pos1 = token.pos;
            accept(DOT);
            if (token.kind == STAR) {
                pid = to(F.at(pos1).Select(pid, names.asterisk));
                nextToken();
                break;
            } else {
                pid = toP(F.at(pos1).Select(pid, ident()));
            }
        } while (token.kind == DOT);
        accept(SEMI);
        return toP(F.at(pos).Import((JCFieldAccess)pid, importStatic));
    }

    /** TypeDeclaration = ClassOrInterfaceOrEnumDeclaration
     *                  | ";"
     */
    JCTree typeDeclaration(JCModifiers mods, Comment docComment) {
        int pos = token.pos;
        if (mods == null && token.kind == SEMI) {
            nextToken();
            return toP(F.at(pos).Skip());
        } else {
            return classOrRecordOrInterfaceOrEnumDeclaration(modifiersOpt(mods), docComment);
        }
    }

    /** ClassOrInterfaceOrEnumDeclaration = ModifiersOpt
     *           (ClassDeclaration | InterfaceDeclaration | EnumDeclaration)
     *  @param mods     Any modifiers starting the class or interface declaration
     *  @param dc       The documentation comment for the class, or null.
     */
    protected JCStatement classOrRecordOrInterfaceOrEnumDeclaration(JCModifiers mods, Comment dc) {
        if (token.kind == CLASS) {
            return classDeclaration(mods, dc);
        } if (isRecordStart()) {
            return recordDeclaration(mods, dc);
        } else if (token.kind == INTERFACE) {
            return interfaceDeclaration(mods, dc);
        } else if (token.kind == ENUM) {
            return enumDeclaration(mods, dc);
        } else {
            int pos = token.pos;
            List<JCTree> errs;
            if (LAX_IDENTIFIER.test(token.kind)) {
                errs = List.of(mods, toP(F.at(pos).Ident(ident())));
                setErrorEndPos(token.pos);
            } else {
                errs = List.of(mods);
            }

            return toP(F.Exec(syntaxError(pos, errs, unexpectedTopLevelDefinitionStartError)));

        }
    }

    /** ClassDeclaration = CLASS Ident TypeParametersOpt [EXTENDS Type]
     *                     [IMPLEMENTS TypeList] ClassBody
     *  @param mods    The modifiers starting the class declaration
     *  @param dc       The documentation comment for the class, or null.
     */
    protected JCClassDecl classDeclaration(JCModifiers mods, Comment dc) {
        int pos = token.pos;
        accept(CLASS);
        Name name = typeName();

        List<JCTypeParameter> typarams = typeParametersOpt();

        JCExpression extending = null;
        if (token.kind == EXTENDS) {
            nextToken();
            extending = parseType();
        }
        List<JCExpression> implementing = List.nil();
        if (token.kind == IMPLEMENTS) {
            nextToken();
            implementing = typeList();
        }
        List<JCExpression> permitting = permitsClause(mods, "class");

        saveDanglingDocComments(dc);

        List<JCTree> defs = classInterfaceOrRecordBody(name, false, false);
        JCClassDecl result = toP(F.at(pos).ClassDef(
            mods, name, typarams, extending, implementing, permitting, defs));
        return attach(result, dc);
    }

    protected JCClassDecl recordDeclaration(JCModifiers mods, Comment dc) {
        int pos = token.pos;
        nextToken();
        mods.flags |= Flags.RECORD;
        Name name = typeName();

        List<JCTypeParameter> typarams = typeParametersOpt();

        List<JCVariableDecl> headerFields = formalParameters(false, true);

        List<JCExpression> implementing = List.nil();
        if (token.kind == IMPLEMENTS) {
            nextToken();
            implementing = typeList();
        }

        saveDanglingDocComments(dc);

        List<JCTree> defs = classInterfaceOrRecordBody(name, false, true);
        java.util.List<JCVariableDecl> fields = new ArrayList<>();
        for (JCVariableDecl field : headerFields) {
            fields.add(field);
        }
        for (JCTree def : defs) {
            if (def.hasTag(METHODDEF)) {
                JCMethodDecl methDef = (JCMethodDecl) def;
                if (methDef.name == names.init && methDef.params.isEmpty() && (methDef.mods.flags & Flags.COMPACT_RECORD_CONSTRUCTOR) != 0) {
                    ListBuffer<JCVariableDecl> tmpParams = new ListBuffer<>();
                    for (JCVariableDecl param : headerFields) {
                        tmpParams.add(F.at(param)
                                // we will get flags plus annotations from the record component
                                .VarDef(F.Modifiers(Flags.PARAMETER | Flags.GENERATED_MEMBER | Flags.MANDATED | param.mods.flags & Flags.VARARGS,
                                        param.mods.annotations),
                                param.name, param.vartype, null));
                    }
                    methDef.params = tmpParams.toList();
                }
            }
        }
        for (int i = fields.size() - 1; i >= 0; i--) {
            JCVariableDecl field = fields.get(i);
            defs = defs.prepend(field);
        }
        JCClassDecl result = toP(F.at(pos).ClassDef(mods, name, typarams, null, implementing, defs));
        return attach(result, dc);
    }

    Name typeName() {
        int pos = token.pos;
        Name name = ident();
        Source source = restrictedTypeNameStartingAtSource(name, pos, true);
        if (source != null) {
            reportSyntaxError(pos, Errors.RestrictedTypeNotAllowed(name, source));
        }
        return name;
    }

    /** InterfaceDeclaration = INTERFACE Ident TypeParametersOpt
     *                         [EXTENDS TypeList] InterfaceBody
     *  @param mods    The modifiers starting the interface declaration
     *  @param dc       The documentation comment for the interface, or null.
     */
    protected JCClassDecl interfaceDeclaration(JCModifiers mods, Comment dc) {
        int pos = token.pos;
        accept(INTERFACE);

        Name name = typeName();

        List<JCTypeParameter> typarams = typeParametersOpt();

        List<JCExpression> extending = List.nil();
        if (token.kind == EXTENDS) {
            nextToken();
            extending = typeList();
        }
        List<JCExpression> permitting = permitsClause(mods, "interface");

        saveDanglingDocComments(dc);

        List<JCTree> defs;
        defs = classInterfaceOrRecordBody(name, true, false);
        JCClassDecl result = toP(F.at(pos).ClassDef(
            mods, name, typarams, null, extending, permitting, defs));
        return attach(result, dc);
    }

    List<JCExpression> permitsClause(JCModifiers mods, String classOrInterface) {
        if (allowSealedTypes && token.kind == IDENTIFIER && token.name() == names.permits) {
            checkSourceLevel(Feature.SEALED_CLASSES);
            if ((mods.flags & Flags.SEALED) == 0) {
                log.error(token.pos, Errors.InvalidPermitsClause(Fragments.ClassIsNotSealed(classOrInterface)));
            }
            nextToken();
            return qualidentList(false);
        }
        return List.nil();
    }

    /** EnumDeclaration = ENUM Ident [IMPLEMENTS TypeList] EnumBody
     *  @param mods    The modifiers starting the enum declaration
     *  @param dc       The documentation comment for the enum, or null.
     */
    protected JCClassDecl enumDeclaration(JCModifiers mods, Comment dc) {
        int pos = token.pos;
        accept(ENUM);

        Name name = typeName();

        int typeNamePos = token.pos;
        List<JCTypeParameter> typarams = typeParametersOpt(true);
        if (typarams == null || !typarams.isEmpty()) {
            int errorPosition = typarams == null
                    ? typeNamePos
                    : typarams.head.pos;
            log.error(DiagnosticFlag.SYNTAX, errorPosition, Errors.EnumCantBeGeneric);
        }

        List<JCExpression> implementing = List.nil();
        if (token.kind == IMPLEMENTS) {
            nextToken();
            implementing = typeList();
        }

        saveDanglingDocComments(dc);

        List<JCTree> defs = enumBody(name);
        mods.flags |= Flags.ENUM;
        JCClassDecl result = toP(F.at(pos).
            ClassDef(mods, name, List.nil(),
                     null, implementing, defs));
        return attach(result, dc);
    }

    /** EnumBody = "{" { EnumeratorDeclarationList } [","]
     *                  [ ";" {ClassBodyDeclaration} ] "}"
     */
    List<JCTree> enumBody(Name enumName) {
        accept(LBRACE);
        ListBuffer<JCTree> defs = new ListBuffer<>();
        boolean wasSemi = false;
        boolean hasStructuralErrors = false;
        boolean wasError = false;
        if (token.kind == COMMA) {
            nextToken();
            if (token.kind == SEMI) {
                wasSemi = true;
                nextToken();
            } else if (token.kind != RBRACE) {
                reportSyntaxError(S.prevToken().endPos,
                                  Errors.Expected2(RBRACE, SEMI));
                wasError = true;
            }
        }
        while (token.kind != RBRACE && token.kind != EOF) {
            if (token.kind == SEMI) {
                accept(SEMI);
                wasSemi = true;
                if (token.kind == RBRACE || token.kind == EOF) break;
            }
            EnumeratorEstimate memberType = estimateEnumeratorOrMember(enumName);
            if (memberType == EnumeratorEstimate.UNKNOWN) {
                memberType = wasSemi ? EnumeratorEstimate.MEMBER
                                     : EnumeratorEstimate.ENUMERATOR;
            }
            if (memberType == EnumeratorEstimate.ENUMERATOR) {
                wasError = false;
                if (wasSemi && !hasStructuralErrors) {
                    reportSyntaxError(token.pos, Errors.EnumConstantNotExpected);
                    hasStructuralErrors = true;
                }
                defs.append(enumeratorDeclaration(enumName));
                if (token.pos <= endPosTable.errorEndPos) {
                    // error recovery
                   skip(false, true, true, false);
                } else {
                    if (token.kind != RBRACE && token.kind != SEMI && token.kind != EOF) {
                        if (token.kind == COMMA) {
                            nextToken();
                        } else {
                            setErrorEndPos(token.pos);
                            reportSyntaxError(S.prevToken().endPos,
                                              Errors.Expected3(COMMA, RBRACE, SEMI));
                            wasError = true;
                        }
                    }
                }
            } else {
                if (!wasSemi && !hasStructuralErrors && !wasError) {
                    reportSyntaxError(token.pos, Errors.EnumConstantExpected);
                    hasStructuralErrors = true;
                }
                wasError = false;
                defs.appendList(classOrInterfaceOrRecordBodyDeclaration(null, enumName,
                                                                false, false));
                if (token.pos <= endPosTable.errorEndPos) {
                    // error recovery
                   skip(false, true, true, false);
                }
            }
        }
        accept(RBRACE);
        return defs.toList();
    }

    @SuppressWarnings("fallthrough")
    private EnumeratorEstimate estimateEnumeratorOrMember(Name enumName) {
        // if we are seeing a record declaration inside of an enum we want the same error message as expected for a
        // let's say an interface declaration inside an enum
        boolean ident = token.kind == TokenKind.IDENTIFIER ||
                        token.kind == TokenKind.UNDERSCORE;
        if (ident && token.name() != enumName &&
                (!allowRecords || !isRecordStart())) {
            Token next = S.token(1);
            switch (next.kind) {
                case LPAREN: case LBRACE: case COMMA: case SEMI:
                    return EnumeratorEstimate.ENUMERATOR;
            }
        }
        switch (token.kind) {
            case IDENTIFIER:
                if (allowRecords && isRecordStart()) {
                    return EnumeratorEstimate.MEMBER;
                }
            case MONKEYS_AT: case LT: case UNDERSCORE:
                return EnumeratorEstimate.UNKNOWN;
            default:
                return EnumeratorEstimate.MEMBER;
        }
    }

    private enum EnumeratorEstimate {
        ENUMERATOR,
        MEMBER,
        UNKNOWN;
    }

    /** EnumeratorDeclaration = AnnotationsOpt [TypeArguments] IDENTIFIER [ Arguments ] [ "{" ClassBody "}" ]
     */
    JCTree enumeratorDeclaration(Name enumName) {
        Comment dc = token.docComment();
        int flags = Flags.PUBLIC|Flags.STATIC|Flags.FINAL|Flags.ENUM;
        if (token.deprecatedFlag()) {
            flags |= Flags.DEPRECATED;
        }
        int pos = token.pos;
        List<JCAnnotation> annotations = annotationsOpt(Tag.ANNOTATION);
        JCModifiers mods = F.at(annotations.isEmpty() ? Position.NOPOS : pos).Modifiers(flags, annotations);
        List<JCExpression> typeArgs = typeArgumentsOpt();
        int identPos = token.pos;
        Name name = ident();
        int createPos = token.pos;

        saveDanglingDocComments(dc);

        List<JCExpression> args = (token.kind == LPAREN)
            ? arguments() : List.nil();

        JCClassDecl body = null;
        if (token.kind == LBRACE) {
            JCModifiers mods1 = F.at(Position.NOPOS).Modifiers(Flags.ENUM);
            List<JCTree> defs = classInterfaceOrRecordBody(names.empty, false, false);
            body = toP(F.at(identPos).AnonymousClassDef(mods1, defs));
        }
        if (args.isEmpty() && body == null)
            createPos = identPos;
        JCIdent ident = F.at(identPos).Ident(enumName);
        JCNewClass create = F.at(createPos).NewClass(null, typeArgs, ident, args, body);
        if (createPos != identPos)
            storeEnd(create, S.prevToken().endPos);
        ident = F.at(identPos).Ident(enumName);
        JCTree result = toP(F.at(pos).VarDef(mods, name, ident, create));
        return attach(result, dc);
    }

    /** TypeList = Type {"," Type}
     */
    List<JCExpression> typeList() {
        ListBuffer<JCExpression> ts = new ListBuffer<>();
        ts.append(parseType());
        while (token.kind == COMMA) {
            nextToken();
            ts.append(parseType());
        }
        return ts.toList();
    }

    /** ClassBody     = "{" {ClassBodyDeclaration} "}"
     *  InterfaceBody = "{" {InterfaceBodyDeclaration} "}"
     */
    List<JCTree> classInterfaceOrRecordBody(Name className, boolean isInterface, boolean isRecord) {
        accept(LBRACE);
        if (token.pos <= endPosTable.errorEndPos) {
            // error recovery
            skip(false, true, false, false);
            if (token.kind == LBRACE)
                nextToken();
            else
                return List.nil();
        }
        ListBuffer<JCTree> defs = new ListBuffer<>();
        while (token.kind != RBRACE && token.kind != EOF) {
            defs.appendList(classOrInterfaceOrRecordBodyDeclaration(null, className, isInterface, isRecord));
            if (token.pos <= endPosTable.errorEndPos) {
               // error recovery
               skip(false, true, true, false);
            }
        }
        accept(RBRACE);
        return defs.toList();
    }

    /** ClassBodyDeclaration =
     *      ";"
     *    | [STATIC] Block
     *    | ModifiersOpt
     *      ( Type Ident
     *        ( VariableDeclaratorsRest ";" | MethodDeclaratorRest )
     *      | VOID Ident VoidMethodDeclaratorRest
     *      | TypeParameters [Annotations]
     *        ( Type Ident MethodDeclaratorRest
     *        | VOID Ident VoidMethodDeclaratorRest
     *        )
     *      | Ident ConstructorDeclaratorRest
     *      | TypeParameters Ident ConstructorDeclaratorRest
     *      | ClassOrInterfaceOrEnumDeclaration
     *      )
     *  InterfaceBodyDeclaration =
     *      ";"
     *    | ModifiersOpt
     *      ( Type Ident
     *        ( ConstantDeclaratorsRest ";" | MethodDeclaratorRest )
     *      | VOID Ident MethodDeclaratorRest
     *      | TypeParameters [Annotations]
     *        ( Type Ident MethodDeclaratorRest
     *        | VOID Ident VoidMethodDeclaratorRest
     *        )
     *      | ClassOrInterfaceOrEnumDeclaration
     *      )
     *
     */
    protected List<JCTree> classOrInterfaceOrRecordBodyDeclaration(JCModifiers mods, Name className,
                                                                   boolean isInterface,
                                                                   boolean isRecord) {
        if (token.kind == SEMI) {
            nextToken();
            return List.nil();
        } else {
            Comment dc = token.docComment();
            int pos = token.pos;
            mods = modifiersOpt(mods);
            if (isDeclaration()) {
                return List.of(classOrRecordOrInterfaceOrEnumDeclaration(mods, dc));
            } else if (token.kind == LBRACE &&
                       (mods.flags & Flags.StandardFlags & ~Flags.STATIC) == 0 &&
                       mods.annotations.isEmpty()) {
                if (isInterface) {
                    log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.InitializerNotAllowed);
                } else if (isRecord && (mods.flags & Flags.STATIC) == 0) {
                    log.error(DiagnosticFlag.SYNTAX, token.pos, Errors.InstanceInitializerNotAllowedInRecords);
                }
                ignoreDanglingComments();   // no declaration with which dangling comments can be associated
                return List.of(block(pos, mods.flags));
            } else if (isDefiniteStatementStartToken()) {
                int startPos = token.pos;
                List<JCStatement> statements = blockStatement();
                return List.of(syntaxError(startPos,
                                           statements,
                                           Errors.StatementNotExpected));
            } else {
                return constructorOrMethodOrFieldDeclaration(mods, className, isInterface, isRecord, dc);
            }
        }
    }

    private List<JCTree> constructorOrMethodOrFieldDeclaration(JCModifiers mods, Name className,
                                                               boolean isInterface,
                                                               boolean isRecord, Comment dc) {
        int pos;
        pos = token.pos;
        List<JCTypeParameter> typarams = typeParametersOpt();
        // if there are type parameters but no modifiers, save the start
        // position of the method in the modifiers.
        if (typarams.nonEmpty() && mods.pos == Position.NOPOS) {
            mods.pos = pos;
            storeEnd(mods, pos);
        }
        List<JCAnnotation> annosAfterParams = annotationsOpt(Tag.ANNOTATION);

        if (annosAfterParams.nonEmpty()) {
            mods.annotations = mods.annotations.appendList(annosAfterParams);
            if (mods.pos == Position.NOPOS)
                mods.pos = mods.annotations.head.pos;
        }

        Token tk = token;
        pos = token.pos;
        JCExpression type;
        boolean isVoid = token.kind == VOID;

        if (isVoid) {
            type = to(F.at(pos).TypeIdent(TypeTag.VOID));
            nextToken();
        } else {
            // method returns types are un-annotated types
            type = unannotatedType(false);
        }

        // Constructor
        if ((token.kind == LPAREN && !isInterface ||
                isRecord && token.kind == LBRACE) && type.hasTag(IDENT)) {
            if (isInterface || tk.name() != className) {
                log.error(DiagnosticFlag.SYNTAX, pos, Errors.InvalidMethDeclRetTypeReq);
            } else if (annosAfterParams.nonEmpty()) {
                illegal(annosAfterParams.head.pos);
            }

            if (isRecord && token.kind == LBRACE) {
                mods.flags |= Flags.COMPACT_RECORD_CONSTRUCTOR;
            }

            return List.of(methodDeclaratorRest(
                    pos, mods, null, names.init, typarams,
                    isInterface, true, isRecord, dc));
        }

        // Record constructor
        if (isRecord && type.hasTag(IDENT) && token.kind == THROWS) {
            // trying to define a compact constructor with a throws clause
            log.error(DiagnosticFlag.SYNTAX, token.pos,
                    Errors.InvalidCanonicalConstructorInRecord(
                            Fragments.Compact,
                            className,
                            Fragments.ThrowsClauseNotAllowedForCanonicalConstructor(Fragments.Compact)));
            skip(false, true, false, false);
            return List.of(methodDeclaratorRest(
                    pos, mods, null, names.init, typarams,
                    isInterface, true, isRecord, dc));
        }

        pos = token.pos;
        Name name = ident();

        // Method
        if (token.kind == LPAREN) {
            return List.of(methodDeclaratorRest(
                    pos, mods, type, name, typarams,
                    isInterface, isVoid, false, dc));
        }

        // Field
        if (!isVoid && typarams.isEmpty()) {
            if (!isRecord || (isRecord && (mods.flags & Flags.STATIC) != 0)) {
                List<JCTree> defs =
                    variableDeclaratorsRest(pos, mods, type, name, isInterface, dc,
                                            new ListBuffer<JCTree>(), false).toList();
                accept(SEMI);
                storeEnd(defs.last(), S.prevToken().endPos);
                return defs;
            }

            int errPos = pos;
            variableDeclaratorsRest(pos, mods, type, name, isInterface, dc,
                    new ListBuffer<JCTree>(), false).toList();
            accept(SEMI);
            return List.of(syntaxError(errPos, null, Errors.RecordCannotDeclareInstanceFields));
         }

         pos = token.pos;
         List<JCTree> err;

         // Error recovery
         if (isVoid || typarams.nonEmpty()) {
             JCMethodDecl m =
                     toP(F.at(pos).MethodDef(mods, name, type, typarams,
                                             List.nil(), List.nil(), null, null));
             attach(m, dc);
             err = List.of(m);
         } else {
             err = List.nil();
         }

         return List.of(syntaxError(token.pos, err, Errors.Expected(LPAREN)));
    }

    private List<JCTree> topLevelMethodOrFieldDeclaration(JCModifiers mods, Comment dc) throws AssertionError {
        int pos = token.pos;
        dc = dc == null ? token.docComment() : dc;
        List<JCTypeParameter> typarams = typeParametersOpt();

        // if there are type parameters but no modifiers, save the start
        // position of the method in the modifiers.
        if (typarams.nonEmpty() && mods.pos == Position.NOPOS) {
            mods.pos = pos;
            storeEnd(mods, pos);
        }

        List<JCAnnotation> annosAfterParams = annotationsOpt(Tag.ANNOTATION);

        if (annosAfterParams.nonEmpty()) {
            mods.annotations = mods.annotations.appendList(annosAfterParams);
            if (mods.pos == Position.NOPOS)
                mods.pos = mods.annotations.head.pos;
        }

        pos = token.pos;
        JCExpression type;
        boolean isVoid = token.kind == VOID;

        if (isVoid) {
            type = to(F.at(pos).TypeIdent(TypeTag.VOID));
            nextToken();
        } else {
            type = unannotatedType(false);
        }

        if (token.kind == IDENTIFIER) {
            pos = token.pos;
            Name name = ident();

            // Method
            if (token.kind == LPAREN) {
                return List.of(methodDeclaratorRest(pos, mods, type, name, typarams,
                        false, isVoid, false, dc));
            }

            // Field
            if (!isVoid && typarams.isEmpty() && (token.kind == EQ || token.kind == SEMI)) {
                List<JCTree> defs =
                        variableDeclaratorsRest(pos, mods, type, name, false, dc,
                                new ListBuffer<JCTree>(), false).toList();
                accept(SEMI);
                storeEnd(defs.last(), S.prevToken().endPos);

                return defs;
            }
        } else if (token.kind == LPAREN && type.hasTag(IDENT)) {
            log.error(DiagnosticFlag.SYNTAX, pos, Errors.InvalidMethDeclRetTypeReq);

            return List.of(methodDeclaratorRest(
                    pos, mods, null, names.init, typarams,
                    false, true, false, dc));
        }

        return List.of(F.Erroneous());
    }

    protected boolean isDeclaration() {
        return isDeclaration(allowRecords);
    }

    private boolean isDeclaration(boolean allowRecords) {
        return token.kind == CLASS ||
               token.kind == INTERFACE ||
               token.kind == ENUM ||
               isRecordStart() && allowRecords;
    }

    /**
     * {@return true if and only if the current token is definitelly a token that
     *  starts a statement.}
     */
    private boolean isDefiniteStatementStartToken() {
        return switch (token.kind) {
            case IF, WHILE, DO, SWITCH, RETURN, TRY, FOR, ASSERT, BREAK,
                 CONTINUE, THROW -> true;
            default -> false;
        };
    }

    protected boolean isRecordStart() {
        if (token.kind == IDENTIFIER && token.name() == names.record && peekToken(TokenKind.IDENTIFIER)) {
            checkSourceLevel(Feature.RECORDS);
            return true;
        } else {
            return false;
        }
    }

    protected boolean isNonSealedClassStart(boolean local) {
        if (isNonSealedIdentifier(token, 0)) {
            Token next = S.token(3);
            return allowedAfterSealedOrNonSealed(next, local, true);
        }
        return false;
    }

    protected boolean isNonSealedIdentifier(Token someToken, int lookAheadOffset) {
        if (someToken.name() == names.non && peekToken(lookAheadOffset, TokenKind.SUB, TokenKind.IDENTIFIER)) {
            Token tokenSub = S.token(lookAheadOffset + 1);
            Token tokenSealed = S.token(lookAheadOffset + 2);
            if (someToken.endPos == tokenSub.pos &&
                    tokenSub.endPos == tokenSealed.pos &&
                    tokenSealed.name() == names.sealed) {
                checkSourceLevel(Feature.SEALED_CLASSES);
                return true;
            }
        }
        return false;
    }

    protected boolean isSealedClassStart(boolean local) {
        if (token.name() == names.sealed) {
            Token next = S.token(1);
            if (allowedAfterSealedOrNonSealed(next, local, false)) {
                checkSourceLevel(Feature.SEALED_CLASSES);
                return true;
            }
        }
        return false;
    }

    private boolean allowedAfterSealedOrNonSealed(Token next, boolean local, boolean currentIsNonSealed) {
        return local ?
            switch (next.kind) {
                case MONKEYS_AT -> {
                    Token afterNext = S.token(2);
                    yield afterNext.kind != INTERFACE || currentIsNonSealed;
                }
                case ABSTRACT, FINAL, STRICTFP, CLASS, INTERFACE, ENUM -> true;
                default -> false;
            } :
            switch (next.kind) {
                case MONKEYS_AT -> {
                    Token afterNext = S.token(2);
                    yield afterNext.kind != INTERFACE || currentIsNonSealed;
                }
                case PUBLIC, PROTECTED, PRIVATE, ABSTRACT, STATIC, FINAL, STRICTFP, CLASS, INTERFACE, ENUM -> true;
                case IDENTIFIER -> isNonSealedIdentifier(next, currentIsNonSealed ? 3 : 1) || next.name() == names.sealed;
                default -> false;
            };
    }

    /** MethodDeclaratorRest =
     *      FormalParameters BracketsOpt [THROWS TypeList] ( MethodBody | [DEFAULT AnnotationValue] ";")
     *  VoidMethodDeclaratorRest =
     *      FormalParameters [THROWS TypeList] ( MethodBody | ";")
     *  ConstructorDeclaratorRest =
     *      "(" FormalParameterListOpt ")" [THROWS TypeList] MethodBody
     */
    protected JCTree methodDeclaratorRest(int pos,
                              JCModifiers mods,
                              JCExpression type,
                              Name name,
                              List<JCTypeParameter> typarams,
                              boolean isInterface, boolean isVoid,
                              boolean isRecord,
                              Comment dc) {
        if (isInterface) {
            if ((mods.flags & Flags.PRIVATE) != 0) {
                checkSourceLevel(Feature.PRIVATE_INTERFACE_METHODS);
            }
        }
        JCVariableDecl prevReceiverParam = this.receiverParam;
        try {
            this.receiverParam = null;
            // Parsing formalParameters sets the receiverParam, if present
            List<JCVariableDecl> params = List.nil();
            List<JCExpression> thrown = List.nil();
            boolean unclosedParameterList;
            if (!isRecord || name != names.init || token.kind == LPAREN) {
                params = formalParameters();
                unclosedParameterList = token.pos == endPosTable.errorEndPos;
                if (!isVoid) type = bracketsOpt(type);
                if (token.kind == THROWS) {
                    nextToken();
                    thrown = qualidentList(true);
                }
            } else {
                unclosedParameterList = false;
            }

            saveDanglingDocComments(dc);

            JCBlock body = null;
            JCExpression defaultValue;
            if (token.kind == LBRACE) {
                body = block();
                defaultValue = null;
            } else {
                if (token.kind == DEFAULT) {
                    accept(DEFAULT);
                    defaultValue = annotationValue();
                    accept(SEMI);
                } else {
                    defaultValue = null;
                    accept(SEMI, tk -> Errors.Expected2(LBRACE, SEMI));
                }
                if (token.pos <= endPosTable.errorEndPos) {
                    // error recovery
                    // look if there is a probable missing opening brace,
                    // and if yes, parse as a block
                    boolean parseAsBlock = openingBraceMissing(unclosedParameterList);

                    if (parseAsBlock) {
                        body = block();
                    }
                }
            }

            JCMethodDecl result =
                    toP(F.at(pos).MethodDef(mods, name, type, typarams,
                                            receiverParam, params, thrown,
                                            body, defaultValue));
            return attach(result, dc);
        } finally {
            this.receiverParam = prevReceiverParam;
        }
    }

    /**
     * After seeing a method header, and not seeing an opening left brace,
     * attempt to estimate if acting as if the left brace was present and
     * parsing the upcoming code will get better results than not parsing
     * the code as a block.
     *
     * The estimate is as follows:
     * - tokens are skipped until member, statement or identifier is found,
     * - then, if there is a left brace, parse as a block,
     * - otherwise, if the head was broken, do not parse as a block,
     * - otherwise, look at the next token and:
     *   - if it definitelly starts a statement, parse as a block,
     *   - otherwise, if it is a closing/right brace, count opening and closing
     *     braces in the rest of the file, to see if imaginarily "adding" an opening
     *     brace would lead to a balanced count - if yes, parse as a block,
     *   - otherwise, speculatively parse the following code as a block, and if
     *     it contains statements that cannot be members, parse as a block,
     *   - otherwise, don't parse as a block.
     *
     * @param unclosedParameterList whether there was a serious problem in the
     *                              parameters list
     * @return true if and only if the following code should be parsed as a block.
     */
    private boolean openingBraceMissing(boolean unclosedParameterList) {
        skip(false, true, !unclosedParameterList, !unclosedParameterList);

        if (token.kind == LBRACE) {
            return true;
        } else if (unclosedParameterList) {
            return false;
        } else {
            return switch (token.kind) {
                //definitelly sees a statement:
                case CASE, DEFAULT, IF, FOR, WHILE, DO, TRY, SWITCH,
                    RETURN, THROW, BREAK, CONTINUE, ELSE, FINALLY,
                    CATCH, THIS, SUPER, NEW -> true;
                case RBRACE -> {
                    //check if adding an opening brace would balance out
                    //the opening and closing braces:
                    int braceBalance = 1;
                    VirtualScanner virtualScanner = new VirtualScanner(S);

                    virtualScanner.nextToken();

                    while (virtualScanner.token().kind != TokenKind.EOF) {
                        switch (virtualScanner.token().kind) {
                            case LBRACE -> braceBalance++;
                            case RBRACE -> braceBalance--;
                        }
                        virtualScanner.nextToken();
                    }

                    yield braceBalance == 0;
                }
                default -> {
                    //speculatively try to parse as a block, and check
                    //if the result would suggest there is a block
                    //e.g.: it contains a statement that is not
                    //a member declaration
                    JavacParser speculative = new VirtualParser(this);
                    JCBlock speculativeResult =
                            speculative.block();
                    if (!speculativeResult.stats.isEmpty()) {
                        JCStatement last = speculativeResult.stats.last();
                        yield !speculativeResult.stats.stream().allMatch(s -> s.hasTag(VARDEF) ||
                                s.hasTag(CLASSDEF) ||
                                s.hasTag(BLOCK) ||
                                s == last) ||
                            !(last instanceof JCExpressionStatement exprStatement &&
                            exprStatement.expr.hasTag(ERRONEOUS));
                    } else {
                        yield false;
                    }
                }
            };
        }
    }

    /** QualidentList = [Annotations] Qualident {"," [Annotations] Qualident}
     */
    List<JCExpression> qualidentList(boolean allowAnnos) {
        ListBuffer<JCExpression> ts = new ListBuffer<>();

        List<JCAnnotation> typeAnnos = allowAnnos ? typeAnnotationsOpt() : List.nil();
        JCExpression qi = qualident(allowAnnos);
        if (!typeAnnos.isEmpty()) {
            JCExpression at = insertAnnotationsToMostInner(qi, typeAnnos, false);
            ts.append(at);
        } else {
            ts.append(qi);
        }
        while (token.kind == COMMA) {
            nextToken();

            typeAnnos = allowAnnos ? typeAnnotationsOpt() : List.nil();
            qi = qualident(allowAnnos);
            if (!typeAnnos.isEmpty()) {
                JCExpression at = insertAnnotationsToMostInner(qi, typeAnnos, false);
                ts.append(at);
            } else {
                ts.append(qi);
            }
        }
        return ts.toList();
    }

    /**
     *  {@literal
     *  TypeParametersOpt = ["<" TypeParameter {"," TypeParameter} ">"]
     *  }
     */
    protected List<JCTypeParameter> typeParametersOpt() {
        return typeParametersOpt(false);
    }
    /** Parses a potentially empty type parameter list if needed with `allowEmpty`.
     *  The caller is free to choose the desirable error message in this (erroneous) case.
     */
    protected List<JCTypeParameter> typeParametersOpt(boolean parseEmpty) {
        if (token.kind == LT) {
            ListBuffer<JCTypeParameter> typarams = new ListBuffer<>();
            nextToken();

            if (parseEmpty && token.kind == GT) {
                accept(GT);
                return null;
            }

            typarams.append(typeParameter());
            while (token.kind == COMMA) {
                nextToken();
                typarams.append(typeParameter());
            }
            accept(GT);
            return typarams.toList();
        } else {
            return List.nil();
        }
    }

    /**
     *  {@literal
     *  TypeParameter = [Annotations] TypeVariable [TypeParameterBound]
     *  TypeParameterBound = EXTENDS Type {"&" Type}
     *  TypeVariable = Ident
     *  }
     */
    JCTypeParameter typeParameter() {
        int pos = token.pos;
        List<JCAnnotation> annos = typeAnnotationsOpt();
        Name name = typeName();
        ListBuffer<JCExpression> bounds = new ListBuffer<>();
        if (token.kind == EXTENDS) {
            nextToken();
            bounds.append(parseType());
            while (token.kind == AMP) {
                nextToken();
                bounds.append(parseType());
            }
        }
        return toP(F.at(pos).TypeParameter(name, bounds.toList(), annos));
    }

    /** FormalParameters = "(" [ FormalParameterList ] ")"
     *  FormalParameterList = [ FormalParameterListNovarargs , ] LastFormalParameter
     *  FormalParameterListNovarargs = [ FormalParameterListNovarargs , ] FormalParameter
     */
    List<JCVariableDecl> formalParameters() {
        return formalParameters(false, false);
    }
    List<JCVariableDecl> formalParameters(boolean lambdaParameters, boolean recordComponents) {
        ListBuffer<JCVariableDecl> params = new ListBuffer<>();
        JCVariableDecl lastParam;
        accept(LPAREN);
        if (token.kind != RPAREN) {
            this.allowThisIdent = !lambdaParameters && !recordComponents;
            lastParam = formalParameter(lambdaParameters, recordComponents);
            if (lastParam.nameexpr != null) {
                this.receiverParam = lastParam;
            } else {
                params.append(lastParam);
            }
            this.allowThisIdent = false;
            while (token.kind == COMMA) {
                if ((lastParam.mods.flags & Flags.VARARGS) != 0) {
                    log.error(DiagnosticFlag.SYNTAX, lastParam, Errors.VarargsMustBeLast);
                }
                nextToken();
                params.append(lastParam = formalParameter(lambdaParameters, recordComponents));
            }
        }
        if (token.kind == RPAREN) {
            nextToken();
        } else {
            setErrorEndPos(token.pos);
            reportSyntaxError(S.prevToken().endPos, Errors.Expected3(COMMA, RPAREN, LBRACKET));
        }
        return params.toList();
    }

    List<JCVariableDecl> implicitParameters(boolean hasParens) {
        if (hasParens) {
            accept(LPAREN);
        }
        ListBuffer<JCVariableDecl> params = new ListBuffer<>();
        if (token.kind != RPAREN && token.kind != ARROW) {
            params.append(implicitParameter());
            while (token.kind == COMMA) {
                nextToken();
                params.append(implicitParameter());
            }
        }
        if (hasParens) {
            accept(RPAREN);
        }
        return params.toList();
    }

    JCModifiers optFinal(long flags) {
        JCModifiers mods = modifiersOpt();
        checkNoMods(mods.flags & ~(Flags.FINAL | Flags.DEPRECATED));
        mods.flags |= flags;
        return mods;
    }

    /**
     * Inserts the annotations (and possibly a new array level)
     * to the left-most type in an array or nested type.
     *
     * When parsing a type like {@code @B Outer.Inner @A []}, the
     * {@code @A} annotation should target the array itself, while
     * {@code @B} targets the nested type {@code Outer}.
     *
     * Currently the parser parses the annotation first, then
     * the array, and then inserts the annotation to the left-most
     * nested type.
     *
     * When {@code createNewLevel} is true, then a new array
     * level is inserted as the most inner type, and have the
     * annotations target it.  This is useful in the case of
     * varargs, e.g. {@code String @A [] @B ...}, as the parser
     * first parses the type {@code String @A []} then inserts
     * a new array level with {@code @B} annotation.
     */
    private JCExpression insertAnnotationsToMostInner(
            JCExpression type, List<JCAnnotation> annos,
            boolean createNewLevel) {
        int origEndPos = getEndPos(type);
        JCExpression mostInnerType = type;
        JCArrayTypeTree mostInnerArrayType = null;
        while (TreeInfo.typeIn(mostInnerType).hasTag(TYPEARRAY)) {
            mostInnerArrayType = (JCArrayTypeTree) TreeInfo.typeIn(mostInnerType);
            mostInnerType = mostInnerArrayType.elemtype;
        }

        if (createNewLevel) {
            mostInnerType = to(F.at(token.pos).TypeArray(mostInnerType));
            origEndPos = getEndPos(mostInnerType);
        }

        JCExpression mostInnerTypeToReturn = mostInnerType;
        if (annos.nonEmpty()) {
            JCExpression lastToModify = mostInnerType;

            while (TreeInfo.typeIn(mostInnerType).hasTag(SELECT) ||
                    TreeInfo.typeIn(mostInnerType).hasTag(TYPEAPPLY)) {
                while (TreeInfo.typeIn(mostInnerType).hasTag(SELECT)) {
                    lastToModify = mostInnerType;
                    mostInnerType = ((JCFieldAccess) TreeInfo.typeIn(mostInnerType)).getExpression();
                }
                while (TreeInfo.typeIn(mostInnerType).hasTag(TYPEAPPLY)) {
                    lastToModify = mostInnerType;
                    mostInnerType = ((JCTypeApply) TreeInfo.typeIn(mostInnerType)).clazz;
                }
            }

            mostInnerType = F.at(annos.head.pos).AnnotatedType(annos, mostInnerType);

            if (TreeInfo.typeIn(lastToModify).hasTag(TYPEAPPLY)) {
                ((JCTypeApply) TreeInfo.typeIn(lastToModify)).clazz = mostInnerType;
            } else if (TreeInfo.typeIn(lastToModify).hasTag(SELECT)) {
                ((JCFieldAccess) TreeInfo.typeIn(lastToModify)).selected = mostInnerType;
            } else {
                // We never saw a SELECT or TYPEAPPLY, return the annotated type.
                mostInnerTypeToReturn = mostInnerType;
            }
        }

        if (mostInnerArrayType == null) {
            return mostInnerTypeToReturn;
        } else {
            mostInnerArrayType.elemtype = mostInnerTypeToReturn;
            return storeEnd(type, origEndPos);
        }
    }

    /** FormalParameter = { FINAL | '@' Annotation } Type VariableDeclaratorId
     *  LastFormalParameter = { FINAL | '@' Annotation } Type '...' Ident | FormalParameter
     */
    protected JCVariableDecl formalParameter(boolean lambdaParameter, boolean recordComponent) {
        JCModifiers mods = !recordComponent ? optFinal(Flags.PARAMETER) : modifiersOpt();
        if (recordComponent && mods.flags != 0) {
            log.error(mods.pos, Errors.RecordCantDeclareFieldModifiers);
        }
        if (recordComponent) {
            mods.flags |= Flags.RECORD | Flags.FINAL | Flags.PRIVATE | Flags.GENERATED_MEMBER;
        }
        // need to distinguish between vararg annos and array annos
        // look at typeAnnotationsPushedBack comment
        this.permitTypeAnnotationsPushBack = true;
        JCExpression type = parseType(lambdaParameter);
        this.permitTypeAnnotationsPushBack = false;

        if (token.kind == ELLIPSIS) {
            List<JCAnnotation> varargsAnnos = typeAnnotationsPushedBack;
            typeAnnotationsPushedBack = List.nil();
            mods.flags |= Flags.VARARGS;
            // insert var arg type annotations
            type = insertAnnotationsToMostInner(type, varargsAnnos, true);
            nextToken();
        } else {
            // if not a var arg, then typeAnnotationsPushedBack should be null
            if (typeAnnotationsPushedBack.nonEmpty()) {
                reportSyntaxError(typeAnnotationsPushedBack.head.pos, Errors.IllegalStartOfType);
            }
            typeAnnotationsPushedBack = List.nil();
        }
        return variableDeclaratorId(mods, type, false, lambdaParameter, recordComponent);
    }

    protected JCVariableDecl implicitParameter() {
        JCModifiers mods = F.at(token.pos).Modifiers(Flags.PARAMETER);
        return variableDeclaratorId(mods, null, false, true, false);
    }

/* ---------- auxiliary methods -------------- */
    /** Check that given tree is a legal expression statement.
     */
    protected JCExpression checkExprStat(JCExpression t) {
        if (!TreeInfo.isExpressionStatement(t)) {
            JCExpression ret = F.at(t.pos).Erroneous(List.<JCTree>of(t));
            log.error(DiagnosticFlag.SYNTAX, ret, Errors.NotStmt);
            return ret;
        } else {
            return t;
        }
    }

    /** Return precedence of operator represented by token,
     *  -1 if token is not a binary operator. @see TreeInfo.opPrec
     */
    static int prec(TokenKind token) {
        JCTree.Tag oc = optag(token);
        return (oc != NO_TAG) ? TreeInfo.opPrec(oc) : -1;
    }

    /**
     * Return the lesser of two positions, making allowance for either one
     * being unset.
     */
    static int earlier(int pos1, int pos2) {
        if (pos1 == Position.NOPOS)
            return pos2;
        if (pos2 == Position.NOPOS)
            return pos1;
        return (pos1 < pos2 ? pos1 : pos2);
    }

    /** Return operation tag of binary operator represented by token,
     *  No_TAG if token is not a binary operator.
     */
    static JCTree.Tag optag(TokenKind token) {
        switch (token) {
        case BARBAR:
            return OR;
        case AMPAMP:
            return AND;
        case BAR:
            return BITOR;
        case BAREQ:
            return BITOR_ASG;
        case CARET:
            return BITXOR;
        case CARETEQ:
            return BITXOR_ASG;
        case AMP:
            return BITAND;
        case AMPEQ:
            return BITAND_ASG;
        case EQEQ:
            return JCTree.Tag.EQ;
        case BANGEQ:
            return NE;
        case LT:
            return JCTree.Tag.LT;
        case GT:
            return JCTree.Tag.GT;
        case LTEQ:
            return LE;
        case GTEQ:
            return GE;
        case LTLT:
            return SL;
        case LTLTEQ:
            return SL_ASG;
        case GTGT:
            return SR;
        case GTGTEQ:
            return SR_ASG;
        case GTGTGT:
            return USR;
        case GTGTGTEQ:
            return USR_ASG;
        case PLUS:
            return JCTree.Tag.PLUS;
        case PLUSEQ:
            return PLUS_ASG;
        case SUB:
            return MINUS;
        case SUBEQ:
            return MINUS_ASG;
        case STAR:
            return MUL;
        case STAREQ:
            return MUL_ASG;
        case SLASH:
            return DIV;
        case SLASHEQ:
            return DIV_ASG;
        case PERCENT:
            return MOD;
        case PERCENTEQ:
            return MOD_ASG;
        case INSTANCEOF:
            return TYPETEST;
        default:
            return NO_TAG;
        }
    }

    /** Return operation tag of unary operator represented by token,
     *  No_TAG if token is not a binary operator.
     */
    static JCTree.Tag unoptag(TokenKind token) {
        switch (token) {
        case PLUS:
            return POS;
        case SUB:
            return NEG;
        case BANG:
            return NOT;
        case TILDE:
            return COMPL;
        case PLUSPLUS:
            return PREINC;
        case SUBSUB:
            return PREDEC;
        default:
            return NO_TAG;
        }
    }

    /** Return type tag of basic type represented by token,
     *  NONE if token is not a basic type identifier.
     */
    static TypeTag typetag(TokenKind token) {
        switch (token) {
        case BYTE:
            return TypeTag.BYTE;
        case CHAR:
            return TypeTag.CHAR;
        case SHORT:
            return TypeTag.SHORT;
        case INT:
            return TypeTag.INT;
        case LONG:
            return TypeTag.LONG;
        case FLOAT:
            return TypeTag.FLOAT;
        case DOUBLE:
            return TypeTag.DOUBLE;
        case BOOLEAN:
            return TypeTag.BOOLEAN;
        default:
            return TypeTag.NONE;
        }
    }

    void checkSourceLevel(Feature feature) {
        checkSourceLevel(token.pos, feature);
    }

    protected void checkSourceLevel(int pos, Feature feature) {
        if (preview.isPreview(feature) && !preview.isEnabled()) {
            //preview feature without --preview flag, error
            log.error(pos, preview.disabledError(feature));
        } else if (!feature.allowedInSource(source)) {
            //incompatible source level, error
            log.error(pos, feature.error(source.name));
        } else if (preview.isPreview(feature)) {
            //use of preview feature, warn
            preview.warnPreview(pos, feature);
        }
    }

    private void updateUnexpectedTopLevelDefinitionStartError(boolean hasPackageDecl) {
        //TODO: proper tests for this logic (and updates):
        if (parseModuleInfo) {
            unexpectedTopLevelDefinitionStartError = Errors.ExpectedModuleOrOpen;
        } else if (Feature.IMPLICIT_CLASSES.allowedInSource(source) && !hasPackageDecl) {
            unexpectedTopLevelDefinitionStartError = Errors.ClassMethodOrFieldExpected;
        } else if (allowRecords) {
            unexpectedTopLevelDefinitionStartError = Errors.Expected4(CLASS, INTERFACE, ENUM, "record");
        } else {
            unexpectedTopLevelDefinitionStartError = Errors.Expected3(CLASS, INTERFACE, ENUM);
        }
    }

    /**
     * A straightforward {@link EndPosTable} implementation.
     */
    protected static class SimpleEndPosTable extends AbstractEndPosTable {

        private final IntHashTable endPosMap = new IntHashTable();

        @Override
        public <T extends JCTree> T storeEnd(T tree, int endpos) {
            endPosMap.put(tree, Math.max(endpos, errorEndPos));
            return tree;
        }

        @Override
        public int getEndPos(JCTree tree) {
            int value = endPosMap.get(tree);
            // As long as Position.NOPOS==-1, this just returns value.
            return (value == -1) ? Position.NOPOS : value;
        }

        @Override
        public int replaceTree(JCTree oldTree, JCTree newTree) {
            int pos = endPosMap.remove(oldTree);
            if (pos != -1 && newTree != null) {
                storeEnd(newTree, pos);
            }
            return pos;
        }
    }

    /**
     * A minimal implementation that only stores what's required.
     */
    protected static class MinimalEndPosTable extends SimpleEndPosTable {

        @Override
        public <T extends JCTree> T storeEnd(T tree, int endpos) {
            switch (tree.getTag()) {
            case MODULEDEF:
            case PACKAGEDEF:
            case CLASSDEF:
            case METHODDEF:
            case VARDEF:
                break;
            default:
                return tree;
            }
            return super.storeEnd(tree, endpos);
        }
    }

    protected abstract static class AbstractEndPosTable implements EndPosTable {

        /**
         * Store the last error position.
         */
        public int errorEndPos = Position.NOPOS;

        @Override
        public void setErrorEndPos(int errPos) {
            if (errPos > errorEndPos) {
                errorEndPos = errPos;
            }
        }
    }
}
