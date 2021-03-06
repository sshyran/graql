/*
 * Copyright (C) 2021 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package graql.lang.parser;

import grakn.common.collection.Either;
import grakn.common.collection.Pair;
import graql.grammar.GraqlBaseVisitor;
import graql.grammar.GraqlLexer;
import graql.grammar.GraqlParser;
import graql.lang.common.GraqlArg;
import graql.lang.common.GraqlToken;
import graql.lang.common.exception.GraqlException;
import graql.lang.pattern.Conjunction;
import graql.lang.pattern.Definable;
import graql.lang.pattern.Disjunction;
import graql.lang.pattern.Negation;
import graql.lang.pattern.Pattern;
import graql.lang.pattern.constraint.ThingConstraint;
import graql.lang.pattern.constraint.TypeConstraint;
import graql.lang.pattern.schema.Rule;
import graql.lang.pattern.variable.BoundVariable;
import graql.lang.pattern.variable.ConceptVariable;
import graql.lang.pattern.variable.ThingVariable;
import graql.lang.pattern.variable.TypeVariable;
import graql.lang.pattern.variable.UnboundVariable;
import graql.lang.query.GraqlCompute;
import graql.lang.query.GraqlDefine;
import graql.lang.query.GraqlDelete;
import graql.lang.query.GraqlInsert;
import graql.lang.query.GraqlMatch;
import graql.lang.query.GraqlQuery;
import graql.lang.query.GraqlUndefine;
import graql.lang.query.GraqlUpdate;
import graql.lang.query.builder.Computable;
import graql.lang.query.builder.Sortable;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static grakn.common.collection.Collections.pair;
import static graql.lang.common.exception.ErrorMessage.ILLEGAL_GRAMMAR;
import static graql.lang.common.exception.ErrorMessage.ILLEGAL_STATE;
import static graql.lang.common.util.Strings.unescapeRegex;
import static graql.lang.pattern.variable.UnboundVariable.hidden;
import static java.util.stream.Collectors.toList;

/**
 * Graql query string parser to produce Graql Java objects
 */
public class Parser extends GraqlBaseVisitor {

    private static final Set<String> GRAQL_KEYWORDS = getKeywords();

    private static Set<String> getKeywords() {
        final HashSet<String> keywords = new HashSet<>();

        for (int i = 1; i <= GraqlLexer.VOCABULARY.getMaxTokenType(); i++) {
            if (GraqlLexer.VOCABULARY.getLiteralName(i) != null) {
                final String name = GraqlLexer.VOCABULARY.getLiteralName(i);
                keywords.add(name.replaceAll("'", ""));
            }
        }

        return Collections.unmodifiableSet(keywords);
    }

    private <CONTEXT extends ParserRuleContext, RETURN> RETURN parse(
            String graqlString, Function<GraqlParser, CONTEXT> parserMethod, Function<CONTEXT, RETURN> visitor
    ) {
        if (graqlString == null || graqlString.isEmpty()) {
            throw GraqlException.of("Query String is NULL or Empty");
        }

        final ErrorListener errorListener = ErrorListener.of(graqlString);
        final CharStream charStream = CharStreams.fromString(graqlString);
        final GraqlLexer lexer = new GraqlLexer(charStream);

        lexer.removeErrorListeners();
        lexer.addErrorListener(errorListener);

        final CommonTokenStream tokens = new CommonTokenStream(lexer);
        final GraqlParser parser = new GraqlParser(tokens);

        parser.removeErrorListeners();
        parser.addErrorListener(errorListener);

        // BailErrorStrategy + SLL is a very fast parsing strategy for queries
        // that are expected to be correct. However, it may not be able to
        // provide detailed/useful error message, if at all.
        parser.setErrorHandler(new BailErrorStrategy());
        parser.getInterpreter().setPredictionMode(PredictionMode.SLL);

        CONTEXT queryContext;
        try {
            queryContext = parserMethod.apply(parser);
        } catch (ParseCancellationException e) {
            // We parse the query one more time, with "strict strategy" :
            // DefaultErrorStrategy + LL_EXACT_AMBIG_DETECTION
            // This was not set to default parsing strategy, but it is useful
            // to produce detailed/useful error message
            parser.setErrorHandler(new DefaultErrorStrategy());
            parser.getInterpreter().setPredictionMode(PredictionMode.LL_EXACT_AMBIG_DETECTION);
            queryContext = parserMethod.apply(parser);

            throw GraqlException.of(errorListener.toString());
        }

        return visitor.apply(queryContext);
    }

    @SuppressWarnings("unchecked")
    public <T extends GraqlQuery> T parseQueryEOF(String queryString) {
        return (T) parse(queryString, GraqlParser::eof_query, this::visitEof_query);
    }

    @SuppressWarnings("unchecked")
    public <T extends GraqlQuery> Stream<T> parseQueriesEOF(String queryString) {
        return (Stream<T>) parse(queryString, GraqlParser::eof_queries, this::visitEof_queries);
    }

    public Pattern parsePatternEOF(String patternString) {
        return parse(patternString, GraqlParser::eof_pattern, this::visitEof_pattern);
    }

    public List<? extends Pattern> parsePatternsEOF(String patternsString) {
        return parse(patternsString, GraqlParser::eof_patterns, this::visitEof_patterns);
    }

    public List<Definable> parseDefinablesEOF(String definablesString) {
        return parse(definablesString, GraqlParser::eof_definables, this::visitEof_definables);
    }

    public BoundVariable parseVariableEOF(String variableString) {
        return parse(variableString, GraqlParser::eof_variable, this::visitEof_variable);
    }

    public Definable parseSchemaRuleEOF(String ruleString) {
        return parse(ruleString, GraqlParser::eof_schema_rule, this::visitEof_schema_rule);
    }

    // GLOBAL HELPER METHODS ===================================================

    private UnboundVariable getVar(TerminalNode variable) {
        // Remove '$' prefix
        final String name = variable.getSymbol().getText().substring(1);

        if (name.equals(GraqlToken.Char.UNDERSCORE.toString())) {
            return UnboundVariable.anonymous();
        } else {
            return UnboundVariable.named(name);
        }
    }

    // PARSER VISITORS =========================================================

    @Override
    public GraqlQuery visitEof_query(GraqlParser.Eof_queryContext ctx) {
        return visitQuery(ctx.query());
    }

    @Override
    public Stream<? extends GraqlQuery> visitEof_queries(GraqlParser.Eof_queriesContext ctx) {
        return ctx.query().stream().map(this::visitQuery);
    }

    @Override
    public Pattern visitEof_pattern(GraqlParser.Eof_patternContext ctx) {
        return visitPattern(ctx.pattern());
    }

    @Override
    public List<? extends Pattern> visitEof_patterns(GraqlParser.Eof_patternsContext ctx) {
        return visitPatterns(ctx.patterns());
    }

    @Override
    public List<Definable> visitEof_definables(GraqlParser.Eof_definablesContext ctx) {
        return ctx.definables().definable().stream().map(this::visitDefinable).collect(toList());
    }

    @Override
    public BoundVariable visitEof_variable(GraqlParser.Eof_variableContext ctx) {
        return visitPattern_variable(ctx.pattern_variable());
    }

    @Override
    public Rule visitEof_schema_rule(GraqlParser.Eof_schema_ruleContext ctx) {
        return visitSchema_rule(ctx.schema_rule());
    }

    // GRAQL QUERIES ===========================================================

    @Override
    public GraqlQuery visitQuery(GraqlParser.QueryContext ctx) {
        if (ctx.query_define() != null) {
            return visitQuery_define(ctx.query_define());

        } else if (ctx.query_undefine() != null) {
            return visitQuery_undefine(ctx.query_undefine());

        } else if (ctx.query_insert() != null) {
            return visitQuery_insert(ctx.query_insert());

        } else if (ctx.query_delete_or_update() != null) {
            return visitQuery_delete_or_update(ctx.query_delete_or_update()).apply(q -> q, q -> q);
        } else if (ctx.query_match() != null) {
            return visitQuery_match(ctx.query_match());

        } else if (ctx.query_match_aggregate() != null) {
            return visitQuery_match_aggregate(ctx.query_match_aggregate());

        } else if (ctx.query_match_group() != null) {
            return visitQuery_match_group(ctx.query_match_group());

        } else if (ctx.query_match_group_agg() != null) {
            return visitQuery_match_group_agg(ctx.query_match_group_agg());

        } else if (ctx.query_compute() != null) {
            return visitQuery_compute(ctx.query_compute());

        } else {
            throw GraqlException.of(ILLEGAL_GRAMMAR.message(ctx.getText()));
        }
    }

    @Override
    public GraqlDefine visitQuery_define(GraqlParser.Query_defineContext ctx) {
        final List<Definable> definables = visitDefinables(ctx.definables());
        return new GraqlDefine(definables);
    }

    @Override
    public GraqlUndefine visitQuery_undefine(GraqlParser.Query_undefineContext ctx) {
        final List<Definable> definables = visitDefinables(ctx.definables());
        return new GraqlUndefine(definables);
    }

    @Override
    public Rule visitSchema_rule(GraqlParser.Schema_ruleContext ctx) {
        final String label = ctx.label().getText();
        if (ctx.patterns() != null && ctx.variable_thing_any() != null) {
            final List<? extends Pattern> when = visitPatterns(ctx.patterns());
            final ThingVariable<?> then = visitVariable_thing_any(ctx.variable_thing_any());
            return new Rule(label, new Conjunction<>(when), then);
        } else {
            return new Rule(label);
        }
    }

    @Override
    public GraqlInsert visitQuery_insert(GraqlParser.Query_insertContext ctx) {
        if (ctx.patterns() != null) {
            return new GraqlMatch.Unfiltered(visitPatterns(ctx.patterns()))
                    .insert(visitVariable_things(ctx.variable_things()));
        } else {
            return new GraqlInsert(visitVariable_things(ctx.variable_things()));
        }
    }

    @Override
    public Either<GraqlDelete, GraqlUpdate> visitQuery_delete_or_update(GraqlParser.Query_delete_or_updateContext ctx) {
        GraqlDelete delete = new GraqlMatch.Unfiltered(visitPatterns(ctx.patterns()))
                .delete(visitVariable_things(ctx.variable_things(0)));
        if (ctx.INSERT() == null) {
            return Either.first(delete);
        } else {
            assert ctx.variable_things().size() == 2;
            return Either.second(delete.insert(visitVariable_things(ctx.variable_things(1))));
        }
    }

    @Override
    public GraqlMatch visitQuery_match(GraqlParser.Query_matchContext ctx) {
        GraqlMatch match = new GraqlMatch.Unfiltered(visitPatterns(ctx.patterns()));

        if (ctx.modifiers() != null) {
            List<UnboundVariable> variables = new ArrayList<>();
            Sortable.Sorting sorting = null;
            Long offset = null, limit = null;

            if (ctx.modifiers().filter() != null) variables = this.visitFilter(ctx.modifiers().filter());
            if (ctx.modifiers().sort() != null) {
                final UnboundVariable var = getVar(ctx.modifiers().sort().VAR_());
                sorting = ctx.modifiers().sort().ORDER_() == null
                        ? new Sortable.Sorting(var)
                        : new Sortable.Sorting(var, GraqlArg.Order.of(ctx.modifiers().sort().ORDER_().getText()));
            }
            if (ctx.modifiers().offset() != null) offset = getLong(ctx.modifiers().offset().LONG_());
            if (ctx.modifiers().limit() != null) limit = getLong(ctx.modifiers().limit().LONG_());
            match = new GraqlMatch(match.conjunction(), variables, sorting, offset, limit);
        }

        return match;
    }

    /**
     * Visits the aggregate query node in the parsed syntax tree and builds the
     * appropriate aggregate query object
     *
     * @param ctx reference to the parsed aggregate query string
     * @return An AggregateQuery object
     */
    @Override
    public GraqlMatch.Aggregate visitQuery_match_aggregate(GraqlParser.Query_match_aggregateContext ctx) {
        final GraqlParser.Match_aggregateContext function = ctx.match_aggregate();

        return visitQuery_match(ctx.query_match()).aggregate(
                GraqlToken.Aggregate.Method.of(function.aggregate_method().getText()),
                function.VAR_() != null ? getVar(function.VAR_()) : null
        );
    }

    @Override
    public GraqlMatch.Group visitQuery_match_group(GraqlParser.Query_match_groupContext ctx) {
        final UnboundVariable var = getVar(ctx.match_group().VAR_());
        return visitQuery_match(ctx.query_match()).group(var);
    }

    @Override
    public GraqlMatch.Group.Aggregate visitQuery_match_group_agg(GraqlParser.Query_match_group_aggContext ctx) {
        final UnboundVariable var = getVar(ctx.match_group().VAR_());
        final GraqlParser.Match_aggregateContext function = ctx.match_aggregate();

        return visitQuery_match(ctx.query_match()).group(var).aggregate(
                GraqlToken.Aggregate.Method.of(function.aggregate_method().getText()),
                function.VAR_() != null ? getVar(function.VAR_()) : null
        );
    }

    // GET QUERY MODIFIERS ==========================================

    @Override
    public List<UnboundVariable> visitFilter(GraqlParser.FilterContext ctx) {
        return ctx.VAR_().stream().map(this::getVar).collect(toList());
    }

    // COMPUTE QUERY ===========================================================

    @Override
    public GraqlCompute visitQuery_compute(GraqlParser.Query_computeContext ctx) {

        if (ctx.compute_conditions().conditions_count() != null) {
            return visitConditions_count(ctx.compute_conditions().conditions_count());
        } else if (ctx.compute_conditions().conditions_value() != null) {
            return visitConditions_value(ctx.compute_conditions().conditions_value());
        } else if (ctx.compute_conditions().conditions_path() != null) {
            return visitConditions_path(ctx.compute_conditions().conditions_path());
        } else if (ctx.compute_conditions().conditions_central() != null) {
            return visitConditions_central(ctx.compute_conditions().conditions_central());
        } else if (ctx.compute_conditions().conditions_cluster() != null) {
            return visitConditions_cluster(ctx.compute_conditions().conditions_cluster());
        } else {
            throw GraqlException.of(ILLEGAL_GRAMMAR.message(ctx.getText()));
        }
    }

    @Override
    public GraqlCompute.Statistics.Count visitConditions_count(GraqlParser.Conditions_countContext ctx) {
        GraqlCompute.Statistics.Count compute = new GraqlCompute.Builder().count();
        if (ctx.input_count() != null) {
            compute = compute.in(visitLabels(ctx.input_count().compute_scope().labels()));
        }
        return compute;
    }

    @Override
    public GraqlCompute.Statistics.Value visitConditions_value(GraqlParser.Conditions_valueContext ctx) {
        GraqlCompute.Statistics.Value compute;
        final GraqlToken.Compute.Method method = GraqlToken.Compute.Method.of(ctx.compute_method().getText());

        if (method == null) {
            throw GraqlException.of(ILLEGAL_GRAMMAR.message(ctx.getText()));
        } else if (method.equals(GraqlToken.Compute.Method.MAX)) {
            compute = new GraqlCompute.Builder().max();
        } else if (method.equals(GraqlToken.Compute.Method.MIN)) {
            compute = new GraqlCompute.Builder().min();
        } else if (method.equals(GraqlToken.Compute.Method.MEAN)) {
            compute = new GraqlCompute.Builder().mean();
        } else if (method.equals(GraqlToken.Compute.Method.MEDIAN)) {
            compute = new GraqlCompute.Builder().median();
        } else if (method.equals(GraqlToken.Compute.Method.SUM)) {
            compute = new GraqlCompute.Builder().sum();
        } else if (method.equals(GraqlToken.Compute.Method.STD)) {
            compute = new GraqlCompute.Builder().std();
        } else {
            throw GraqlException.of(ILLEGAL_GRAMMAR.message(ctx.getText()));
        }

        for (GraqlParser.Input_valueContext valueCtx : ctx.input_value()) {
            if (valueCtx.compute_target() != null) {
                compute = compute.of(visitLabels(valueCtx.compute_target().labels()));
            } else if (valueCtx.compute_scope() != null) {
                compute = compute.in(visitLabels(valueCtx.compute_scope().labels()));
            } else {
                throw GraqlException.of(ILLEGAL_GRAMMAR.message(ctx.getText()));
            }
        }

        return compute;
    }

    @Override
    public GraqlCompute.Path visitConditions_path(GraqlParser.Conditions_pathContext ctx) {
        GraqlCompute.Path compute = new GraqlCompute.Builder().path();

        for (GraqlParser.Input_pathContext pathCtx : ctx.input_path()) {

            if (pathCtx.compute_direction() != null) {
                final String id = pathCtx.compute_direction().IID_().getText();
                if (pathCtx.compute_direction().FROM() != null) {
                    compute = compute.from(id);
                } else if (pathCtx.compute_direction().TO() != null) {
                    compute = compute.to(id);
                }
            } else if (pathCtx.compute_scope() != null) {
                compute = compute.in(visitLabels(pathCtx.compute_scope().labels()));
            } else {
                throw GraqlException.of(ILLEGAL_GRAMMAR.message(ctx.getText()));
            }
        }

        return compute;
    }

    @Override
    public GraqlCompute.Centrality visitConditions_central(GraqlParser.Conditions_centralContext ctx) {
        GraqlCompute.Centrality compute = new GraqlCompute.Builder().centrality();

        for (GraqlParser.Input_centralContext centralityCtx : ctx.input_central()) {
            if (centralityCtx.compute_target() != null) {
                compute = compute.of(visitLabels(centralityCtx.compute_target().labels()));
            } else if (centralityCtx.compute_scope() != null) {
                compute = compute.in(visitLabels(centralityCtx.compute_scope().labels()));
            } else if (centralityCtx.compute_config() != null) {
                compute = (GraqlCompute.Centrality) setComputeConfig(compute, centralityCtx.compute_config());
            } else {
                throw GraqlException.of(ILLEGAL_GRAMMAR.message(ctx.getText()));
            }
        }

        return compute;
    }

    @Override
    public GraqlCompute.Cluster visitConditions_cluster(GraqlParser.Conditions_clusterContext ctx) {
        GraqlCompute.Cluster compute = new GraqlCompute.Builder().cluster();

        for (GraqlParser.Input_clusterContext clusterCtx : ctx.input_cluster()) {
            if (clusterCtx.compute_scope() != null) {
                compute = compute.in(visitLabels(clusterCtx.compute_scope().labels()));
            } else if (clusterCtx.compute_config() != null) {
                compute = (GraqlCompute.Cluster) setComputeConfig(compute, clusterCtx.compute_config());
            } else {
                throw GraqlException.of(ILLEGAL_GRAMMAR.message(ctx.getText()));
            }
        }

        return compute;
    }

    private Computable.Configurable setComputeConfig(Computable.Configurable compute, GraqlParser.Compute_configContext ctx) {
        if (ctx.USING() != null) {
            compute = compute.using(GraqlArg.Algorithm.of(ctx.compute_algorithm().getText()));
        } else if (ctx.WHERE() != null) {
            compute = compute.where(visitCompute_args(ctx.compute_args()));
        }

        return compute;
    }

    @Override
    public List<GraqlCompute.Argument> visitCompute_args(GraqlParser.Compute_argsContext ctx) {

        final List<GraqlParser.Compute_argContext> argContextList = new ArrayList<>();
        final List<GraqlCompute.Argument> argList = new ArrayList<>();

        if (ctx.compute_arg() != null) {
            argContextList.add(ctx.compute_arg());
        } else if (ctx.compute_args_array() != null) {
            argContextList.addAll(ctx.compute_args_array().compute_arg());
        }

        for (GraqlParser.Compute_argContext argContext : argContextList) {
            if (argContext.MIN_K() != null) {
                argList.add(GraqlCompute.Argument.minK(getLong(argContext.LONG_())));

            } else if (argContext.K() != null) {
                argList.add(GraqlCompute.Argument.k(getLong(argContext.LONG_())));

            } else if (argContext.SIZE() != null) {
                argList.add(GraqlCompute.Argument.size(getLong(argContext.LONG_())));

            } else if (argContext.CONTAINS() != null) {
                argList.add(GraqlCompute.Argument.contains(argContext.IID_().getText()));
            }
        }

        return argList;
    }

    // QUERY PATTERNS ==========================================================

    @Override
    public List<Pattern> visitPatterns(GraqlParser.PatternsContext ctx) {
        return ctx.pattern().stream().map(this::visitPattern).collect(toList());
    }

    @Override
    public Pattern visitPattern(GraqlParser.PatternContext ctx) {
        if (ctx.pattern_variable() != null) {
            return visitPattern_variable(ctx.pattern_variable());
        } else if (ctx.pattern_disjunction() != null) {
            return visitPattern_disjunction(ctx.pattern_disjunction());
        } else if (ctx.pattern_conjunction() != null) {
            return visitPattern_conjunction(ctx.pattern_conjunction());
        } else if (ctx.pattern_negation() != null) {
            return visitPattern_negation(ctx.pattern_negation());
        } else {
            throw GraqlException.of(ILLEGAL_GRAMMAR.message(ctx.getText()));
        }
    }

    @Override
    public Disjunction<? extends Pattern> visitPattern_disjunction(GraqlParser.Pattern_disjunctionContext ctx) {
        final List<Pattern> patterns = ctx.patterns().stream().map(patternsContext -> {
            final List<Pattern> nested = visitPatterns(patternsContext);
            if (nested.size() > 1) return new Conjunction<>(nested);
            else return nested.get(0);
        }).collect(toList());

        assert patterns.size() > 1;

        return new Disjunction<>(patterns);
    }

    @Override
    public Conjunction<? extends Pattern> visitPattern_conjunction(GraqlParser.Pattern_conjunctionContext ctx) {
        return new Conjunction<>(visitPatterns(ctx.patterns()));
    }

    @Override
    public Negation<? extends Pattern> visitPattern_negation(GraqlParser.Pattern_negationContext ctx) {
        final List<Pattern> patterns = visitPatterns(ctx.patterns());
        if (patterns.size() == 1) return new Negation<>(patterns.get(0));
        else return new Negation<>(new Conjunction<>(patterns));
    }

    // QUERY DEFINABLES ========================================================

    @Override
    public Definable visitDefinable(GraqlParser.DefinableContext ctx) {
        if (ctx.variable_type() != null) {
            return visitVariable_type(ctx.variable_type());
        } else {
            return visitSchema_rule(ctx.schema_rule());
        }
    }

    @Override
    public List<Definable> visitDefinables(GraqlParser.DefinablesContext ctx) {
        return ctx.definable().stream().map(this::visitDefinable).collect(toList());
    }


    // VARIABLE PATTERNS =======================================================

    @Override
    public BoundVariable visitPattern_variable(GraqlParser.Pattern_variableContext ctx) {
        if (ctx.variable_thing_any() != null) {
            return this.visitVariable_thing_any(ctx.variable_thing_any());
        } else if (ctx.variable_type() != null) {
            return visitVariable_type(ctx.variable_type());
        } else if (ctx.variable_concept() != null) {
            return visitVariable_concept(ctx.variable_concept());
        } else {
            throw GraqlException.of(ILLEGAL_GRAMMAR.message(ctx.getText()));
        }
    }

    // CONCEPT VARIABLES =======================================================

    @Override
    public ConceptVariable visitVariable_concept(GraqlParser.Variable_conceptContext ctx) {
        return getVar(ctx.VAR_(0)).is(getVar(ctx.VAR_(1)));
    }

    // TYPE VARIABLES ==========================================================

    @Override
    public TypeVariable visitVariable_type(GraqlParser.Variable_typeContext ctx) {
        TypeVariable type = visitType_any(ctx.type_any()).apply(
                scopedLabel -> hidden().constrain(new TypeConstraint.Label(scopedLabel.first(), scopedLabel.second())),
                UnboundVariable::toType
        );

        for (GraqlParser.Type_constraintContext constraint : ctx.type_constraint()) {
            if (constraint.ABSTRACT() != null) {
                type = type.isAbstract();
            } else if (constraint.SUB_() != null) {
                final GraqlToken.Constraint sub = GraqlToken.Constraint.of(constraint.SUB_().getText());
                type = type.constrain(new TypeConstraint.Sub(visitType_any(constraint.type_any()), sub == GraqlToken.Constraint.SUBX));
            } else if (constraint.OWNS() != null) {
                final Either<String, UnboundVariable> overridden = constraint.AS() == null ? null : visitType(constraint.type(1));
                type = type.constrain(new TypeConstraint.Owns(visitType(constraint.type(0)), overridden, constraint.IS_KEY() != null));
            } else if (constraint.PLAYS() != null) {
                final Either<String, UnboundVariable> overridden = constraint.AS() == null ? null : visitType(constraint.type(0));
                type = type.constrain(new TypeConstraint.Plays(visitType_scoped(constraint.type_scoped()), overridden));
            } else if (constraint.RELATES() != null) {
                final Either<String, UnboundVariable> overridden = constraint.AS() == null ? null : visitType(constraint.type(1));
                type = type.constrain(new TypeConstraint.Relates(visitType(constraint.type(0)), overridden));
            } else if (constraint.VALUE() != null) {
                type = type.value(GraqlArg.ValueType.of(constraint.value_type().getText()));
            } else if (constraint.REGEX() != null) {
                type = type.regex(getRegex(constraint.STRING_()));
            } else if (constraint.TYPE() != null) {
                final Pair<String, String> scopedLabel = visitLabel_any(constraint.label_any());
                type = type.constrain(new TypeConstraint.Label(scopedLabel.first(), scopedLabel.second()));
            } else {
                throw GraqlException.of(ILLEGAL_GRAMMAR.message(constraint.getText()));
            }
        }

        return type;
    }

    // THING VARIABLES =========================================================

    @Override
    public List<ThingVariable<?>> visitVariable_things(GraqlParser.Variable_thingsContext ctx) {
        return ctx.variable_thing_any().stream().map(this::visitVariable_thing_any).collect(toList());
    }

    @Override
    public ThingVariable<?> visitVariable_thing_any(GraqlParser.Variable_thing_anyContext ctx) {
        if (ctx.variable_thing() != null) {
            return this.visitVariable_thing(ctx.variable_thing());
        } else if (ctx.variable_relation() != null) {
            return this.visitVariable_relation(ctx.variable_relation());
        } else if (ctx.variable_attribute() != null) {
            return this.visitVariable_attribute(ctx.variable_attribute());
        } else {
            throw GraqlException.of(ILLEGAL_GRAMMAR.message(ctx.getText()));
        }
    }

    @Override
    public ThingVariable.Thing visitVariable_thing(GraqlParser.Variable_thingContext ctx) {
        final UnboundVariable unscoped = getVar(ctx.VAR_());
        ThingVariable.Thing thing = null;

        if (ctx.ISA_() != null) {
            thing = unscoped.constrain(getIsaConstraint(ctx.ISA_(), ctx.type()));
        } else if (ctx.IID() != null) {
            thing = unscoped.iid(ctx.IID_().getText());
        }

        if (ctx.attributes() != null) {
            for (ThingConstraint.Has hasAttribute : visitAttributes(ctx.attributes())) {
                if (thing == null) thing = unscoped.constrain(hasAttribute);
                else thing = thing.constrain(hasAttribute);
            }
        }
        return thing;
    }

    @Override
    public ThingVariable.Relation visitVariable_relation(GraqlParser.Variable_relationContext ctx) {
        final UnboundVariable unscoped;
        if (ctx.VAR_() != null) unscoped = getVar(ctx.VAR_());
        else unscoped = hidden();

        ThingVariable.Relation relation = unscoped.constrain(visitRelation(ctx.relation()));
        if (ctx.ISA_() != null) relation = relation.constrain(getIsaConstraint(ctx.ISA_(), ctx.type()));

        if (ctx.attributes() != null) {
            for (ThingConstraint.Has hasAttribute : visitAttributes(ctx.attributes())) {
                relation = relation.constrain(hasAttribute);
            }
        }
        return relation;
    }

    @Override
    public ThingVariable.Attribute visitVariable_attribute(GraqlParser.Variable_attributeContext ctx) {
        final UnboundVariable unscoped;
        if (ctx.VAR_() != null) unscoped = getVar(ctx.VAR_());
        else unscoped = hidden();

        ThingVariable.Attribute attribute = unscoped.constrain(visitPredicate(ctx.predicate()));
        if (ctx.ISA_() != null) attribute = attribute.constrain(getIsaConstraint(ctx.ISA_(), ctx.type()));

        if (ctx.attributes() != null) {
            for (ThingConstraint.Has hasAttribute : visitAttributes(ctx.attributes())) {
                attribute = attribute.constrain(hasAttribute);
            }
        }
        return attribute;
    }

    private ThingConstraint.Isa getIsaConstraint(TerminalNode isaToken, GraqlParser.TypeContext ctx) {
        final GraqlToken.Constraint isa = GraqlToken.Constraint.of(isaToken.getText());

        if (isa != null && isa.equals(GraqlToken.Constraint.ISA)) {
            return new ThingConstraint.Isa(visitType(ctx), false);
        } else if (isa != null && isa.equals(GraqlToken.Constraint.ISAX)) {
            return new ThingConstraint.Isa(visitType(ctx), true);
        } else {
            throw GraqlException.of(ILLEGAL_GRAMMAR.message(ctx.getText()));
        }
    }

    // ATTRIBUTE STATEMENT CONSTRUCT ===============================================

    @Override
    public List<ThingConstraint.Has> visitAttributes(GraqlParser.AttributesContext ctx) {
        return ctx.attribute().stream().map(this::visitAttribute).collect(toList());
    }

    @Override
    public ThingConstraint.Has visitAttribute(GraqlParser.AttributeContext ctx) {
        if (ctx.label() != null) {
            if (ctx.VAR_() != null) return new ThingConstraint.Has(ctx.label().getText(), getVar(ctx.VAR_()));
            if (ctx.predicate() != null)
                return new ThingConstraint.Has(ctx.label().getText(), visitPredicate(ctx.predicate()));
        } else if (ctx.VAR_() != null) return new ThingConstraint.Has(getVar(ctx.VAR_()));
        throw GraqlException.of(ILLEGAL_GRAMMAR.message(ctx.getText()));
    }

    // RELATION STATEMENT CONSTRUCT ============================================

    @Override
    public ThingConstraint.Relation visitRelation(GraqlParser.RelationContext ctx) {
        final List<ThingConstraint.Relation.RolePlayer> rolePlayers = new ArrayList<>();

        for (GraqlParser.Role_playerContext rolePlayerCtx : ctx.role_player()) {
            final UnboundVariable player = getVar(rolePlayerCtx.player().VAR_());
            if (rolePlayerCtx.type() != null) {
                final Either<String, UnboundVariable> roleType = visitType(rolePlayerCtx.type());
                rolePlayers.add(new ThingConstraint.Relation.RolePlayer(roleType, player));
            } else {
                rolePlayers.add(new ThingConstraint.Relation.RolePlayer(player));
            }
        }
        return new ThingConstraint.Relation(rolePlayers);
    }

    // TYPE, LABEL, AND IDENTIFIER CONSTRUCTS ==================================

    @Override
    public Either<Pair<String, String>, UnboundVariable> visitType_any(GraqlParser.Type_anyContext ctx) {
        if (ctx.VAR_() != null) return Either.second(getVar(ctx.VAR_()));
        else if (ctx.type() != null)
            return visitType(ctx.type()).apply(s -> Either.first(pair(null, s)), Either::second);
        else if (ctx.type_scoped() != null) return visitType_scoped(ctx.type_scoped());
        else return null;
    }

    @Override
    public Either<Pair<String, String>, UnboundVariable> visitType_scoped(GraqlParser.Type_scopedContext ctx) {
        if (ctx.label_scoped() != null) return Either.first(visitLabel_scoped(ctx.label_scoped()));
        else if (ctx.VAR_() != null) return Either.second(getVar(ctx.VAR_()));
        else return null;
    }

    @Override
    public Either<String, UnboundVariable> visitType(GraqlParser.TypeContext ctx) {
        if (ctx.label() != null) return Either.first(ctx.label().getText());
        else if (ctx.VAR_() != null) return Either.second(getVar(ctx.VAR_()));
        else return null;
    }

    @Override
    public Pair<String, String> visitLabel_any(GraqlParser.Label_anyContext ctx) {
        if (ctx.label() != null) return pair(null, ctx.label().getText());
        else if (ctx.label_scoped() != null) return visitLabel_scoped(ctx.label_scoped());
        else return null;
    }

    @Override
    public Pair<String, String> visitLabel_scoped(GraqlParser.Label_scopedContext ctx) {
        final String[] scopedLabel = ctx.getText().split(":");
        return pair(scopedLabel[0], scopedLabel[1]);
    }

    @Override
    public List<String> visitLabels(GraqlParser.LabelsContext ctx) {
        final List<GraqlParser.LabelContext> labelsList = new ArrayList<>();
        if (ctx.label() != null) labelsList.add(ctx.label());
        else if (ctx.label_array() != null) labelsList.addAll(ctx.label_array().label());
        return labelsList.stream().map(RuleContext::getText).collect(toList());
    }

    // ATTRIBUTE OPERATION CONSTRUCTS ==========================================

    @Override
    public ThingConstraint.Value<?> visitPredicate(GraqlParser.PredicateContext ctx) {
        final GraqlToken.Predicate predicate;
        final Object value;

        if (ctx.value() != null) {
            predicate = GraqlToken.Predicate.Equality.EQ;
            value = visitValue(ctx.value());
        } else if (ctx.predicate_equality() != null) {
            predicate = GraqlToken.Predicate.Equality.of(ctx.predicate_equality().getText());
            if (ctx.predicate_value().value() != null) value = visitValue(ctx.predicate_value().value());
            else if (ctx.predicate_value().VAR_() != null) value = getVar(ctx.predicate_value().VAR_());
            else throw GraqlException.of(ILLEGAL_STATE);
        } else if (ctx.predicate_substring() != null) {
            predicate = GraqlToken.Predicate.SubString.of(ctx.predicate_substring().getText());
            if (ctx.predicate_substring().LIKE() != null) value = getRegex(ctx.STRING_());
            else value = getString(ctx.STRING_());
        } else throw GraqlException.of(ILLEGAL_STATE);

        assert predicate != null;

        if (value instanceof Long) {
            return new ThingConstraint.Value.Long(predicate.asEquality(), (Long) value);
        } else if (value instanceof Double) {
            return new ThingConstraint.Value.Double(predicate.asEquality(), (Double) value);
        } else if (value instanceof Boolean) {
            return new ThingConstraint.Value.Boolean(predicate.asEquality(), (Boolean) value);
        } else if (value instanceof String) {
            return new ThingConstraint.Value.String(predicate, (String) value);
        } else if (value instanceof LocalDateTime) {
            return new ThingConstraint.Value.DateTime(predicate.asEquality(), (LocalDateTime) value);
        } else if (value instanceof UnboundVariable) {
            return new ThingConstraint.Value.Variable(predicate.asEquality(), (UnboundVariable) value);
        } else {
            throw GraqlException.of(ILLEGAL_GRAMMAR.message(ctx.getText()));
        }
    }

    // LITERAL INPUT VALUES ====================================================

    public String getRegex(TerminalNode string) {
        return unescapeRegex(unquoteString(string));
    }

    @Override
    public GraqlArg.ValueType visitValue_type(GraqlParser.Value_typeContext valueClass) {
        if (valueClass.BOOLEAN() != null) {
            return GraqlArg.ValueType.BOOLEAN;
        } else if (valueClass.DATETIME() != null) {
            return GraqlArg.ValueType.DATETIME;
        } else if (valueClass.DOUBLE() != null) {
            return GraqlArg.ValueType.DOUBLE;
        } else if (valueClass.LONG() != null) {
            return GraqlArg.ValueType.LONG;
        } else if (valueClass.STRING() != null) {
            return GraqlArg.ValueType.STRING;
        } else {
            throw GraqlException.of(ILLEGAL_GRAMMAR.message(valueClass));
        }
    }

    @Override
    public Object visitValue(GraqlParser.ValueContext ctx) {
        if (ctx.STRING_() != null) {
            return getString(ctx.STRING_());

        } else if (ctx.LONG_() != null) {
            return getLong(ctx.LONG_());

        } else if (ctx.DOUBLE_() != null) {
            return getDouble(ctx.DOUBLE_());

        } else if (ctx.BOOLEAN_() != null) {
            return getBoolean(ctx.BOOLEAN_());

        } else if (ctx.DATE_() != null) {
            return getDate(ctx.DATE_());

        } else if (ctx.DATETIME_() != null) {
            return getDateTime(ctx.DATETIME_());

        } else {
            throw GraqlException.of(ILLEGAL_GRAMMAR.message(ctx.getText()));
        }
    }

    private String getString(TerminalNode string) {
        String str = string.getText();
        assert str.length() >= 2;
        GraqlToken.Char start = GraqlToken.Char.of(str.substring(0, 1));
        GraqlToken.Char end = GraqlToken.Char.of(str.substring(str.length() - 1));
        assert start != null && end != null;
        assert start.equals(GraqlToken.Char.QUOTE_DOUBLE) || start.equals(GraqlToken.Char.QUOTE_SINGLE);
        assert end.equals(GraqlToken.Char.QUOTE_DOUBLE) || end.equals(GraqlToken.Char.QUOTE_SINGLE);

        // Remove surrounding quotes
        return unquoteString(string);
    }

    private String unquoteString(TerminalNode string) {
        return string.getText().substring(1, string.getText().length() - 1);
    }

    private long getLong(TerminalNode number) {
        try {
            return Long.parseLong(number.getText());
        }
        catch (NumberFormatException e) {
            throw GraqlException.of(ILLEGAL_GRAMMAR.message(number.getText()));
        }
    }

    private double getDouble(TerminalNode real) {
        try {
            return Double.parseDouble(real.getText());
        } catch (NumberFormatException e) {
            throw GraqlException.of(ILLEGAL_GRAMMAR.message(real.getText()));
        }
    }

    private boolean getBoolean(TerminalNode bool) {
        final GraqlToken.Literal literal = GraqlToken.Literal.of(bool.getText());

        if (literal != null && literal.equals(GraqlToken.Literal.TRUE)) {
            return true;

        } else if (literal != null && literal.equals(GraqlToken.Literal.FALSE)) {
            return false;

        } else {
            throw GraqlException.of(ILLEGAL_GRAMMAR.message(bool.getText()));
        }
    }

    private LocalDateTime getDate(TerminalNode date) {
        try {
            return LocalDate.parse(date.getText(), DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
        } catch (DateTimeParseException e) {
            throw GraqlException.of(ILLEGAL_GRAMMAR.message(date.getText()));
        }
    }

    private LocalDateTime getDateTime(TerminalNode dateTime) {
        try {
            return LocalDateTime.parse(dateTime.getText(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            throw GraqlException.of(ILLEGAL_GRAMMAR.message(dateTime.getText()));
        }
    }
}
