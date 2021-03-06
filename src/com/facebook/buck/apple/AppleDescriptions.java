/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.apple.graphql.GraphQLDataDescription;
import com.facebook.buck.cxx.CxxConstructorArg;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal.CycleException;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.ImmutableBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ImmutableBuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Common logic for a {@link com.facebook.buck.rules.Description} that creates Apple target rules.
 */
public class AppleDescriptions {

  public static final Flavor HEADERS = ImmutableFlavor.of("headers");

  private static final BuildRuleType HEADERS_RULE_TYPE = ImmutableBuildRuleType.of("headers");

  /** Utility class: do not instantiate. */
  private AppleDescriptions() {}

  /**
   * Tries to create a {@link BuildRule} based on the flavors of {@code params.getBuildTarget()} and
   * the specified args.
   * If this method does not know how to handle the specified flavors, it returns {@code null}.
   */
  static <A extends AppleNativeTargetDescriptionArg> Optional<BuildRule> createFlavoredRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args,
      AppleConfig appleConfig,
      SourcePathResolver pathResolver,
      TargetSources targetSources) {
    BuildTarget target = params.getBuildTarget();
    if (target.getFlavors().contains(CompilationDatabase.COMPILATION_DATABASE)) {
      BuildRule compilationDatabase = createCompilationDatabase(
          params,
          resolver,
          args,
          appleConfig,
          pathResolver,
          targetSources);
      return Optional.of(compilationDatabase);
    } else if (target.getFlavors().contains(HEADERS)) {
      return Optional.of(createHeadersFlavor(params, pathResolver, args));
    } else {
      return Optional.absent();
    }
  }

  /**
   * @return A rule for making the headers of an Apple target available to other targets.
   */
  static BuildRule createHeadersFlavor(
      BuildRuleParams params,
      SourcePathResolver resolver,
      AppleNativeTargetDescriptionArg args) {
    BuildTarget targetForOriginalRule = params.getBuildTarget();
    if (targetForOriginalRule.isFlavored()) {
      targetForOriginalRule = targetForOriginalRule.getUnflavoredTarget();
    }
    BuildTarget headersTarget = BuildTargets.createFlavoredBuildTarget(
        targetForOriginalRule,
        AppleDescriptions.HEADERS);

    BuildRuleParams headerRuleParams = params.copyWithChanges(
        HEADERS_RULE_TYPE,
        headersTarget,
        /* declaredDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
        Suppliers.ofInstance(params.getExtraDeps()));

    TargetSources targetSources = TargetSources.ofAppleSources(resolver, args.srcs.get());
    ImmutableSortedMap<SourcePath, String> perFileFlags =
        ImmutableSortedMap.copyOf(targetSources.getPerFileFlags());
    boolean useBuckHeaderMaps = args.useBuckHeaderMaps.or(Boolean.FALSE);
    return createSymlinkTree(
        headerRuleParams,
        resolver,
        perFileFlags,
        useBuckHeaderMaps);
  }

  public static Path getPathToHeaders(BuildTarget buildTarget) {
    return BuildTargets.getBinPath(buildTarget, "__%s_public_headers__");
  }

  private static SymlinkTree createSymlinkTree(
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      ImmutableSortedMap<SourcePath, String> perFileFlags,
      boolean useBuckHeaderMaps) {
    // Note that the set of headersToCopy may be empty. If so, the returned rule will be a no-op.
    // TODO(mbolin): Make headersToCopy an ImmutableSortedMap once we clean up the iOS codebase and
    // can guarantee that the keys are unique.
    Map<Path, SourcePath> headersToCopy;
    if (useBuckHeaderMaps) {
      // No need to copy headers because header maps are used.
      headersToCopy = ImmutableSortedMap.of();
    } else {
      // This is a heuristic to get the right header path prefix. Note that ProjectGenerator uses a
      // slightly different heuristic, which is buildTarget.getShortName(), though that is only
      // a fallback when an apple_library() does not specify a header_path_prefix when
      // use_buck_header_maps is True.
      Path headerPathPrefix = params.getBuildTarget().getBasePath().getFileName();

      headersToCopy = Maps.newHashMap();
      Splitter spaceSplitter = Splitter.on(' ').trimResults().omitEmptyStrings();
      Predicate<String> isPublicHeaderFlag = Predicates.equalTo("public");
      for (Map.Entry<SourcePath, String> entry : perFileFlags.entrySet()) {
        String flags = entry.getValue();
        if (Iterables.any(spaceSplitter.split(flags), isPublicHeaderFlag)) {
          SourcePath sourcePath = entry.getKey();
          Path sourcePathName = pathResolver.getPath(sourcePath).getFileName();
          headersToCopy.put(headerPathPrefix.resolve(sourcePathName), sourcePath);
        }
      }
    }

    BuildRuleParams headerParams = params.copyWithDeps(
        /* declaredDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
        Suppliers.ofInstance(params.getExtraDeps()));
    Path root = getPathToHeaders(params.getBuildTarget());
    return new SymlinkTree(headerParams, pathResolver, root, ImmutableMap.copyOf(headersToCopy));
  }

  /**
   * @return A compilation database with entries for the files in the specified
   *     {@code targetSources}.
   */
  private static CompilationDatabase createCompilationDatabase(
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      AppleNativeTargetDescriptionArg args,
      AppleConfig appleConfig,
      SourcePathResolver pathResolver,
      TargetSources targetSources) {
    CompilationDatabaseTraversal traversal = new CompilationDatabaseTraversal(
        params.getTargetGraph(),
        buildRuleResolver);
    Iterable<TargetNode<AppleNativeTargetDescriptionArg>> startNodes = filterAppleNativeTargetNodes(
        FluentIterable
            .from(params.getDeclaredDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .transform(params.getTargetGraph().get())
            .append(params.getTargetGraph().get(params.getBuildTarget())));
    try {
      traversal.traverse(startNodes);
    } catch (CycleException | IOException | InterruptedException e) {
      throw new RuntimeException(e);
    }

    BuildRuleParams compilationDatabaseParams = params.copyWithDeps(
        /* declaredDeps */ Suppliers.ofInstance(traversal.deps.build()),
        Suppliers.ofInstance(params.getExtraDeps()));

    return new CompilationDatabase(
        compilationDatabaseParams,
        pathResolver,
        appleConfig,
        targetSources,
        args.frameworks.get(),
        traversal.includePaths.build(),
        args.prefixHeader);
  }

  private static FluentIterable<TargetNode<AppleNativeTargetDescriptionArg>>
      filterAppleNativeTargetNodes(FluentIterable<TargetNode<?>> fluentIterable) {
    return fluentIterable
        .filter(
            new Predicate<TargetNode<?>>() {
              @Override
              public boolean apply(TargetNode<?> input) {
                return ImmutableSet
                    .of(AppleBinaryDescription.TYPE, AppleLibraryDescription.TYPE)
                    .contains(input.getType());
              }
            })
        .transform(
            new Function<TargetNode<?>, TargetNode<AppleNativeTargetDescriptionArg>>() {
              @Override
              @SuppressWarnings("unchecked")
              public TargetNode<AppleNativeTargetDescriptionArg> apply(TargetNode<?> input) {
                return (TargetNode<AppleNativeTargetDescriptionArg>) input;
              }
            });
  }

  public static ImmutableMap<
      BuildTarget,
      ImmutableSet<TargetNode<GraphQLDataDescription.Arg>>> getTargetsToTransitiveModelDependencies(
      TargetGraph targetGraph) {
    Map<
        BuildTarget,
        Set<TargetNode<GraphQLDataDescription.Arg>>> targetsToTransitiveModelDependencies =
        new HashMap<>();

    for (TargetNode<?> node : targetGraph.getNodes()) {
      if (node.getType().equals(GraphQLDataDescription.TYPE)) {
        TargetNode<GraphQLDataDescription.Arg> model =
            node.castArg(GraphQLDataDescription.Arg.class).get();
        Set<TargetNode<?>> visited = new HashSet<>();
        for (TargetNode<?> dependent : targetGraph.getIncomingNodesFor(model)) {
          addAllTransitiveDependents(
              targetGraph,
              targetsToTransitiveModelDependencies,
              dependent,
              model,
              visited);
        }
      }
    }

    return ImmutableMap.copyOf(
        Maps.transformValues(
            targetsToTransitiveModelDependencies,
            new Function<
                Set<TargetNode<GraphQLDataDescription.Arg>>,
                ImmutableSet<TargetNode<GraphQLDataDescription.Arg>>>() {
              @Override
              public ImmutableSet<TargetNode<GraphQLDataDescription.Arg>> apply(
                  Set<TargetNode<GraphQLDataDescription.Arg>> input) {
                return ImmutableSet.copyOf(input);
              }
            }
        ));
  }

  private static void addAllTransitiveDependents(
      TargetGraph targetGraph,
      Map<
          BuildTarget,
          Set<TargetNode<GraphQLDataDescription.Arg>>> targetsToTransitiveModelDependencies,
      TargetNode<?> node,
      TargetNode<GraphQLDataDescription.Arg> model,
      Set<TargetNode<?>> visited) {
    if (visited.contains(node)) {
      return;
    }
    visited.add(node);
    if (node.getType() != AppleLibraryDescription.TYPE &&
        node.getType() != AppleBinaryDescription.TYPE) {
      return;
    }
    Set<TargetNode<GraphQLDataDescription.Arg>> models =
        targetsToTransitiveModelDependencies.get(node.getBuildTarget());
    if (models == null) {
      models = new HashSet<>();
      targetsToTransitiveModelDependencies.put(node.getBuildTarget(), models);
    }
    models.add(model);

    for (TargetNode<?> dependent : targetGraph.getIncomingNodesFor(node)) {
      addAllTransitiveDependents(
          targetGraph,
          targetsToTransitiveModelDependencies, dependent, model, visited);
    }
  }

  public static BuildTarget getMergedBuildTarget(
      Iterable<? extends HasBuildTarget> targets) {
    ImmutableSortedSet<BuildTarget> sortedTargets = ImmutableSortedSet.copyOf(
        Iterables.transform(targets, HasBuildTarget.TO_TARGET));
    return ImmutableBuildTarget.of(
        Optional.<String>absent(),
        "//buck/synthesized/ios",
        Joiner
            .on('-')
            .join(
                Iterables.transform(
                    sortedTargets,
                    new Function<BuildTarget, String>() {
                      @Override
                      public String apply(BuildTarget input) {
                        return input
                            .getFullyQualifiedName()
                            .replace('/', '-')
                            .replace('.', '-')
                            .replace('+', '-')
                            .replace(' ', '-')
                            .replace(':', '-');
                      }
                    })),
        ImmutableSortedSet.<Flavor>of());
  }

  public static ImmutableMap<
      BuildTarget,
      TargetNode<GraphQLDataDescription.Arg>> mergeGraphQLModels(
      ImmutableMap<
          BuildTarget,
          ImmutableSet<
              TargetNode<GraphQLDataDescription.Arg>>> targetsToTransitiveModelDependencies) {
    ImmutableMap.Builder<BuildTarget, TargetNode<GraphQLDataDescription.Arg>> modelsBuilder =
        ImmutableMap.builder();
    Map<Set<TargetNode<GraphQLDataDescription.Arg>>, TargetNode<GraphQLDataDescription.Arg>>
        mergedNodes = new HashMap<>();
    for (Map.Entry<BuildTarget, ImmutableSet<TargetNode<GraphQLDataDescription.Arg>>> entry :
        targetsToTransitiveModelDependencies.entrySet()) {
      TargetNode<GraphQLDataDescription.Arg> mergedNode = mergedNodes.get(entry.getValue());
      if (mergedNode != null) {
        modelsBuilder.put(entry.getKey(), mergedNode);
        continue;
      }
      mergedNode = mergeGraphQLModels(entry.getValue());
      mergedNodes.put(entry.getValue(), mergedNode);
      modelsBuilder.put(entry.getKey(), mergedNode);
    }

    return modelsBuilder.build();
  }

  public static TargetGraph getSubgraphWithMergedModels(
      final TargetGraph targetGraph,
      ImmutableSet<TargetNode<GraphQLDataDescription.Arg>> mergedModels) {
    final ImmutableSet<TargetNode<?>> unmergedModels = FluentIterable
        .from(targetGraph.getNodes())
        .filter(
            new Predicate<TargetNode<?>>() {
              @Override
              public boolean apply(TargetNode<?> input) {
                return input.getType().equals(GraphQLDataDescription.TYPE);
              }
            })
        .toSet();
    final MutableDirectedGraph<TargetNode<?>> subgraph = new MutableDirectedGraph<>();

    new AbstractBreadthFirstTraversal<TargetNode<?>>(unmergedModels) {
      @Override
      public ImmutableSet<TargetNode<?>> visit(TargetNode<?> node) {
        ImmutableSet<TargetNode<?>> dependencies =
            ImmutableSet.copyOf(targetGraph.getAll(node.getDeps()));
        if (unmergedModels.contains(node)) {
          return dependencies;
        }
        subgraph.addNode(node);
        for (TargetNode<?> dependency : dependencies) {
          subgraph.addEdge(node, dependency);
        }
        return dependencies;
      }
    }.start();

    for (TargetNode<?> mergedModel : mergedModels) {
      subgraph.addNode(mergedModel);
      for (TargetNode<?> dependency : targetGraph.getAll(mergedModel.getDeps())) {
        Preconditions.checkNotNull(targetGraph.get(dependency.getBuildTarget()));
        subgraph.addEdge(mergedModel, dependency);
      }
    }

    return new TargetGraph(subgraph);
  }

  @VisibleForTesting
  static TargetNode<GraphQLDataDescription.Arg> mergeGraphQLModels(
      Set<TargetNode<GraphQLDataDescription.Arg>> models) {
    TargetNode<GraphQLDataDescription.Arg> mergedModel = null;
    for (TargetNode<GraphQLDataDescription.Arg> model : models) {
      if (mergedModel == null) {
        mergedModel = model.withConstructorArg(copyGraphQLArg(model));
        continue;
      }

      GraphQLDataDescription.Arg mergedArg = mergedModel.getConstructorArg();
      GraphQLDataDescription.Arg nextArg = model.getConstructorArg();
      if (!mergedArg.isMergeableWith(nextArg)) {
        throw new HumanReadableException(
            "Attempting to merge two GraphQL models with incompatible args.");
      }

      mergedModel = mergedModel.withConstructorArg(mergedArg.mergeWith(nextArg));
    }
    return Preconditions.checkNotNull(mergedModel).withBuildTarget(getMergedBuildTarget(models));
  }

  @VisibleForTesting
  static GraphQLDataDescription.Arg copyGraphQLArg(TargetNode<GraphQLDataDescription.Arg> node) {
    GraphQLDataDescription.Arg arg = node.getDescription().createUnpopulatedConstructorArg();
    arg.queries = node.getConstructorArg().queries;
    arg.consistencyConfig = node.getConstructorArg().consistencyConfig;
    arg.clientSchemaConfig = node.getConstructorArg().clientSchemaConfig;
    arg.schema = node.getConstructorArg().schema;
    arg.mutations = node.getConstructorArg().mutations;
    arg.modelTags = node.getConstructorArg().modelTags;
    arg.knownIssuesFile = node.getConstructorArg().knownIssuesFile;
    arg.persistIds = node.getConstructorArg().persistIds;
    arg.configs = node.getConstructorArg().configs;
    arg.srcs = node.getConstructorArg().srcs;
    arg.frameworks = node.getConstructorArg().frameworks;
    arg.deps = node.getConstructorArg().deps;
    arg.gid = node.getConstructorArg().gid;
    arg.headerPathPrefix = node.getConstructorArg().headerPathPrefix;
    arg.useBuckHeaderMaps = node.getConstructorArg().useBuckHeaderMaps;
    arg.prefixHeader = node.getConstructorArg().prefixHeader;
    arg.tests = node.getConstructorArg().tests;
    return arg;
  }

  private static class CompilationDatabaseTraversal
      extends AbstractAcyclicDepthFirstPostOrderTraversal<
      TargetNode<AppleNativeTargetDescriptionArg>> {

    private final TargetGraph targetGraph;
    private final BuildRuleResolver buildRuleResolver;
    private final ImmutableSet.Builder<Path> includePaths;
    private final ImmutableSortedSet.Builder<BuildRule> deps;

    private CompilationDatabaseTraversal(
        TargetGraph targetGraph,
        BuildRuleResolver buildRuleResolver) {
      this.targetGraph = targetGraph;
      this.buildRuleResolver = buildRuleResolver;
      this.includePaths = ImmutableSet.builder();
      this.deps = ImmutableSortedSet.naturalOrder();
    }

    @Override
    protected Iterator<TargetNode<AppleNativeTargetDescriptionArg>> findChildren(
        TargetNode<AppleNativeTargetDescriptionArg> node)
        throws IOException, InterruptedException {
      return filterAppleNativeTargetNodes(
          FluentIterable.from(node.getDeclaredDeps()).transform(targetGraph.get())).iterator();
    }

    @Override
    protected void onNodeExplored(TargetNode<AppleNativeTargetDescriptionArg> node)
        throws IOException, InterruptedException {
      if (node.getConstructorArg().getUseBuckHeaderMaps()) {
        // TODO(user): Currently, header maps are created by `buck project`. Eventually they
        // should become build dependencies. When that happens, rule should be added to this.deps.
        Path headerMap = getPathToHeaderMap(node, HeaderMapType.PUBLIC_HEADER_MAP).get();
        includePaths.add(headerMap);
      } else {
        // In this case, we need the #headers flavor of node so the path to its public headers
        // directory can be included. First, we perform a defensive check to make sure that node is
        // an unflavored node because it may not be safe to request the #headers of a flavored node.
        BuildTarget buildTarget = node.getBuildTarget();
        if (buildTarget.isFlavored()) {
          return;
        }

        // Next, we get the #headers flavor of the rule.
        BuildTarget targetForHeaders = BuildTargets.createFlavoredBuildTarget(
            buildTarget,
            AppleDescriptions.HEADERS);
        Optional<BuildRule> buildRule = buildRuleResolver.getRuleOptional(targetForHeaders);
        if (!buildRule.isPresent()) {
          BuildRule newBuildRule = node.getDescription().createBuildRule(
              new BuildRuleParams(
                  targetForHeaders,
                  Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
                  Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
                  node.getRuleFactoryParams().getProjectFilesystem(),
                  node.getRuleFactoryParams().getRuleKeyBuilderFactory(),
                  node.getType(),
                  targetGraph),
              buildRuleResolver,
              node.getConstructorArg());
          buildRuleResolver.addToIndex(newBuildRule);
          buildRule = Optional.of(newBuildRule);
        }
        SymlinkTree headersRule = (SymlinkTree) buildRule.get();

        // Finally, we make sure the rule has public headers before adding it to includePaths.
        Optional<Path> headersDirectory = headersRule.getRootOfSymlinksDirectory();
        if (headersDirectory.isPresent()) {
          includePaths.add(headersDirectory.get());
          deps.add(headersRule);
        }
      }
    }

    @Override
    protected void onTraversalComplete(
        Iterable<TargetNode<AppleNativeTargetDescriptionArg>> nodesInExplorationOrder) {
      // Nothing to do: work is done in onNodeExplored.
    }
  }

  public static Optional<Path> getPathToHeaderMap(
      TargetNode<? extends AppleNativeTargetDescriptionArg> targetNode,
      HeaderMapType headerMapType) {
    if (!targetNode.getConstructorArg().useBuckHeaderMaps.get()) {
      return Optional.absent();
    }

    return Optional.of(
        BuildTargets.getGenPath(
          targetNode.getBuildTarget().getUnflavoredTarget(),
          "%s" + headerMapType.getSuffix()));
  }

  private static Path translateAppleSdkPaths(
      Path path,
      AppleSdkPaths appleSdkPaths) {
    if (path.startsWith("$SDKROOT")) {
      path = appleSdkPaths.getSdkPath().resolve(
          path.subpath(1, path.getNameCount()));
    } else if (path.startsWith("$DEVELOPER_DIR")) {
      path = appleSdkPaths.getDeveloperPath().resolve(
          path.subpath(1, path.getNameCount()));
    }

    return path;
  }

  public static void populateCxxConstructorArg(
      CxxConstructorArg output,
      AppleNativeTargetDescriptionArg arg,
      BuildTarget buildTarget,
      TargetSources targetSources,
      final Optional<AppleSdkPaths> appleSdkPaths) {
    output.srcs = Optional.of(
        Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofLeft(
            ImmutableList.copyOf(targetSources.getSrcPaths())));
    output.headers = Optional.of(
        Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofLeft(
            ImmutableList.copyOf(targetSources.getHeaderPaths())));
    output.compilerFlags = Optional.of(ImmutableList.<String>of());
    output.linkerFlags = Optional.of(ImmutableList.<String>of());
    output.platformLinkerFlags = Optional.of(
        ImmutableList.<Pair<String, ImmutableList<String>>>of());
    output.preprocessorFlags = Optional.of(ImmutableList.<String>of());
    output.langPreprocessorFlags = Optional.of(
        ImmutableMap.<CxxSource.Type, ImmutableList<String>>of());
    if (appleSdkPaths.isPresent()) {
      output.frameworkSearchPaths = arg.frameworks.transform(
          new Function<ImmutableSortedSet<String>, ImmutableList<Path>>() {
            @Override
            public ImmutableList<Path> apply(ImmutableSortedSet<String> frameworkPaths) {
                ImmutableSet.Builder<Path> frameworksSearchPathsBuilder = ImmutableSet.builder();
                for (String frameworkPath : frameworkPaths) {
                  Path parentDirectory = Paths.get(frameworkPath).getParent();
                  if (parentDirectory != null) {
                    frameworksSearchPathsBuilder.add(
                        translateAppleSdkPaths(parentDirectory, appleSdkPaths.get()));
                  }
                }
                return ImmutableList.copyOf(frameworksSearchPathsBuilder.build());
            }
          });
    } else {
      output.frameworkSearchPaths = Optional.of(ImmutableList.<Path>of());
    }
    output.lexSrcs = Optional.of(ImmutableList.<SourcePath>of());
    output.yaccSrcs = Optional.of(ImmutableList.<SourcePath>of());
    output.deps = arg.deps;
    output.headerNamespace = arg.headerPathPrefix.or(
        Optional.of(buildTarget.getShortName()));
  }

}
