package com.vulkairis.test;

public class VulkairisTestRunner {
    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("   Vulkairis Prototype Automated Test Suite      ");
        System.out.println("=================================================");

        int passed = 0;
        int failed = 0;

        // 1. Vulkan Capabilities Tests
        try {
            System.out.println("\n[1/5] Running VulkanCapabilitiesTest...");
            VulkanCapabilitiesTest capsTest = new VulkanCapabilitiesTest();
            capsTest.testCapabilitiesDefaults();
            capsTest.testCompatibilityCheck();
            System.out.println(">>> PASS: VulkanCapabilitiesTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: VulkanCapabilitiesTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 2. Shader Analyzer & Dry-Run Tests
        try {
            System.out.println("\n[2/5] Running ShaderAnalyzerTest...");
            ShaderAnalyzerTest analyzerTest = new ShaderAnalyzerTest();
            analyzerTest.testValidVertexShaderAnalysis();
            analyzerTest.testValidFragmentShaderAnalysis();
            analyzerTest.testUnsupportedRaytracingShader();
            analyzerTest.testUnsupportedGeometryStage();
            analyzerTest.testMalformedShaderGracefulFailure();
            System.out.println(">>> PASS: ShaderAnalyzerTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: ShaderAnalyzerTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 3. Render State Tracker Tests
        try {
            System.out.println("\n[3/5] Running RenderStateTrackerTest...");
            RenderStateTrackerTest trackerTest = new RenderStateTrackerTest();
            trackerTest.testPipelineKeyGeneration();
            trackerTest.testViewportTracking();
            trackerTest.testShortAndUshortElementMapping();
            trackerTest.testByteElementCountMapping();
            trackerTest.testTranslucentBlendAlphaFactors();
            trackerTest.testPipelineKeyIncludesRenderPassAndDepthCompare();
            System.out.println(">>> PASS: RenderStateTrackerTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: RenderStateTrackerTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 4. In-Process Shaderc & SPIR-V Bytecode Compilation Test
        try {
            System.out.println("\n[4/5] Running ShaderCompilerTest...");
            ShaderCompilerTest compilerTest = new ShaderCompilerTest();
            compilerTest.testInProcessShadercCompilation();
            compilerTest.testCenterDepthCompilation();
            compilerTest.testDefaultInitializedUniformsCompile();
            compilerTest.testArrayedSamplerReflection();
            System.out.println(">>> PASS: ShaderCompilerTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: ShaderCompilerTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 5. Reflection + Intercepted-Program Regression Test
        try {
            System.out.println("\n[5/9] Running PipelineRegressionTest...");
            PipelineRegressionTest regressionTest = new PipelineRegressionTest();
            regressionTest.testSpirvReflectionIsNonEmpty();
            regressionTest.testInterceptedProgramResolvesRealShaders();
            regressionTest.testDebugRenderModeRoundTrip();
            System.out.println(">>> PASS: PipelineRegressionTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: PipelineRegressionTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 6. Phase 3: Shader lifecycle state machine
        try {
            System.out.println("\n[6/9] Running ShaderLifecycleTest...");
            ShaderLifecycleTest lifecycleTest = new ShaderLifecycleTest();
            lifecycleTest.testForwardOnlyTransitions();
            lifecycleTest.testIsAtLeast();
            lifecycleTest.testCompiledShaderReachesSpirvValidAndReflected();
            lifecycleTest.testAdvanceLifecycleIsForwardOnly();
            System.out.println(">>> PASS: ShaderLifecycleTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: ShaderLifecycleTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 7. Phase 4: SPIR-V reflection + pipeline compatibility validation
        try {
            System.out.println("\n[7/9] Running ReflectionValidationTest...");
            ReflectionValidationTest reflectionTest = new ReflectionValidationTest();
            reflectionTest.testStageOutputsAreReflected();
            reflectionTest.testCompatiblePairValidates();
            reflectionTest.testMismatchedPairIsRejected();
            reflectionTest.testSpirvValidationResultApi();
            System.out.println(">>> PASS: ReflectionValidationTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: ReflectionValidationTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 8. Phase 7: Centralized render matrices
        try {
            System.out.println("\n[8/9] Running RenderMatricesTest...");
            RenderMatricesTest matricesTest = new RenderMatricesTest();
            matricesTest.testIdentityModelViewProducesExpectedModelViewProjection();
            matricesTest.testInverseViewComposedWithViewIsIdentity();
            matricesTest.testClipSpaceCorrectionMapsDepthAndPreservesY();
            System.out.println(">>> PASS: RenderMatricesTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: RenderMatricesTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 9. Phase 1/6/9 + 11: Controlled-draw gate and descriptor contracts
        try {
            System.out.println("\n[9/10] Running ControlledDrawGateTest + DescriptorContractTest...");
            ControlledDrawGateTest gateTest = new ControlledDrawGateTest();
            gateTest.testNonControlledModeIsRejected();
            gateTest.testNormalModeIsRejectedEvenInWorld();
            gateTest.testControlledModeAllowedInWorldMainPass();
            gateTest.testOpenScreenRejected();
            gateTest.testNonMainPassRejected();
            gateTest.testZeroPassIdRejected();
            gateTest.testNoLevelRejected();
            gateTest.testNoPlayerRejected();
            gateTest.testVisualizerModesParseAndAreControlled();
            gateTest.testVisualizerModesShareWorldGateAndUnknownFailsSafe();
            DescriptorContractTest descriptorTest = new DescriptorContractTest();
            descriptorTest.testCompatibleSpecsProduceNoProblems();
            descriptorTest.testLargerProvidedCountCoversRequiredCount();
            descriptorTest.testMissingBindingIsReported();
            descriptorTest.testTypeMismatchIsReported();
            descriptorTest.testCountMismatchIsReported();
            descriptorTest.testDescribeFailureIncludesPassBindingAndType();
            System.out.println(">>> PASS: ControlledDrawGateTest + DescriptorContractTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: ControlledDrawGateTest + DescriptorContractTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 10. Iris attribute-name + type normalization (VulkanMod interop)
        try {
            System.out.println("\n[10/13] Running IrisAttributeMappingTest...");
            IrisAttributeMappingTest irisTest = new IrisAttributeMappingTest();
            irisTest.testNormalizeIrisAttributeNamesMapsKnownNames();
            irisTest.testNormalizeIrisAttributeNamesMapsInsideDeclaration();
            irisTest.testUnknownIrisNameIsLeftUntouched();
            irisTest.testNormalizeDoesNotRewritePrefixedOrMemberIdentifiers();
            irisTest.testNormalizeIsIdempotent();
            irisTest.testPreprocessNormalizesIrisAttributeNames();
            irisTest.testBoolUniformBecomesIntForVulkanMod();
            irisTest.testNormalizeTypesLeavesBoolSubstringsUntouched();
            System.out.println(">>> PASS: IrisAttributeMappingTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: IrisAttributeMappingTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 11. Dense descriptor binding remap (VulkanMod descriptor layout)
        try {
            System.out.println("\n[11/13] Running DenseBindingRemapTest...");
            DenseBindingRemapTest remapTest = new DenseBindingRemapTest();
            remapTest.testInjectedBlockAndSamplersRemapToDenseZeroBased();
            remapTest.testOrderedMappingIsDeterministic();
            remapTest.testSharedExplicitMapKeepsCrossStageBindingsConsistent();
            remapTest.testSameSamplerNamesGetSameSharedBindingsAcrossStages();
            remapTest.testRemapDoesNotDisturbLocationQualifiers();
            remapTest.testArrayedSamplerKeepsBaseNameAndGetsOneBinding();
            remapTest.testNullAndBlankInputsAreHandled();
            System.out.println(">>> PASS: DenseBindingRemapTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: DenseBindingRemapTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 12. layout(location) injection for VulkanMod-compiled sources
        try {
            System.out.println("\n[12/13] Running LayoutLocationInjectionTest...");
            LayoutLocationInjectionTest layoutTest = new LayoutLocationInjectionTest();
            layoutTest.testVertexInputGetsLocationFromMap();
            layoutTest.testAlreadyQualifiedDeclarationIsNotModified();
            layoutTest.testSharedVaryingLocationsAgreeAcrossStages();
            layoutTest.testFragmentOutputsGetSequentialLocations();
            layoutTest.testQualifierFormsAreHandled();
            layoutTest.testArrayedDeclarationUsesNameBeforeBracket();
            layoutTest.testMissingVertexInputIsLeftUntouched();
            layoutTest.testInjectionIsIdempotent();
            layoutTest.testUniformAndFunctionDeclarationsAreNotTouched();
            layoutTest.testPreprocessOutputUnaffectedByInjection();
            System.out.println(">>> PASS: LayoutLocationInjectionTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: LayoutLocationInjectionTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 13. Opaque sampler/texture/image binding injection for VulkanMod-compiled sources
        try {
            System.out.println("\n[13/14] Running SamplerBindingInjectionTest...");
            SamplerBindingInjectionTest samplerTest = new SamplerBindingInjectionTest();
            samplerTest.testBareSamplerGetsInjectedBinding();
            samplerTest.testExistingBindingIsRewrittenToMappedValue();
            samplerTest.testLayoutWithoutBindingGainsOneAndKeepsOtherQualifiers();
            samplerTest.testArrayedSamplerGetsOneBindingAndKeepsArrayDeclarator();
            samplerTest.testPrecisionQualifierIsPreserved();
            samplerTest.testInjectionIsIdempotent();
            samplerTest.testNonOpaqueUniformIsLeftUntouched();
            samplerTest.testCollectDescriptorNamesSeesBareSamplers();
            samplerTest.testCenterDepthStyleSourceGetsDenseBindings();
            System.out.println(">>> PASS: SamplerBindingInjectionTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: SamplerBindingInjectionTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 14. Block-scoped bool conversion + multi-declarator interface locations
        try {
            System.out.println("\n[14/14] Running BoolScopeAndMultiDeclTest...");
            BoolScopeAndMultiDeclTest boolTest = new BoolScopeAndMultiDeclTest();
            boolTest.testBoolConversionIsScopedToInjectedBlock();
            boolTest.testBoolConversionLeavesSourcesWithoutInjectedBlockUntouched();
            boolTest.testBoolLiteralInsideBlockIsConvertedDefensively();
            boolTest.testNormalizeTypesIsIdempotent();
            boolTest.testNormalizeTypesHandlesNullAndEmpty();
            boolTest.testMultiDeclaratorStatementIsSplitIntoSeparateLocatedDeclarations();
            boolTest.testCollectInterfaceNamesReturnsEveryDeclarator();
            boolTest.testMultiDeclaratorArraysKeepTheirOwnSpecifier();
            boolTest.testMultiDeclaratorInjectionIsIdempotent();
            boolTest.testMultiDeclaratorWithMissingLocationIsLeftUntouched();
            boolTest.testMultiDeclaratorPreservesIndentationAndTrailingComment();
            boolTest.testSingleDeclaratorBehaviourIsUnchanged();
            boolTest.testInjectLayoutLocationsHandlesNull();
            System.out.println(">>> PASS: BoolScopeAndMultiDeclTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: BoolScopeAndMultiDeclTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 15. Phase 1: complete shader-pack program discovery catalog
        try {
            System.out.println("\n[15/15] Running ShaderPackProgramCatalogTest...");
            ShaderPackProgramCatalogTest catalogTest = new ShaderPackProgramCatalogTest();
            catalogTest.testKindDerivation();
            catalogTest.testExecutionOrderFollowsIrisGroupOrder();
            catalogTest.testOrderByExecutionSortsGroupsAndArrayIndex();
            catalogTest.testArrayElementDetection();
            catalogTest.testDescribeIncludesIdentityAndStages();
            System.out.println(">>> PASS: ShaderPackProgramCatalogTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: ShaderPackProgramCatalogTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 16. Phase 3: render-graph dependency compilation
        try {
            System.out.println("\n[16/16] Running RenderGraphTest...");
            RenderGraphTest graphTest = new RenderGraphTest();
            graphTest.testLinearChainResolvesInDependencyOrder();
            graphTest.testCycleIsRejectedWithPath();
            graphTest.testMrtPassDeclaresMultipleColorOutputs();
            graphTest.testDepthOnlyPassHasNoColorOutputs();
            graphTest.testPingPongCompositeChain();
            graphTest.testDiamondDependencyOrdersBeforeJoin();
            graphTest.testLifetimeAndRecycling();
            graphTest.testReadWithoutProducerIsRejected();
            graphTest.testExternalResourceReadIsAllowed();
            graphTest.testDuplicatePassNameRejected();
            graphTest.testComputePassWithStorageImage();
            graphTest.testUsageKindMismatchIsRejected();
            graphTest.testFrameGraphDumpMentionsPassesAndResources();
            graphTest.testPingPongReuseWithSkippedIndexCompilesInOrder();
            System.out.println(">>> PASS: RenderGraphTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: RenderGraphTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 17. Phase 0: known-good shader-pack fixture harness
        try {
            System.out.println("\n[17/17] Running ShaderTestHarnessTest...");
            ShaderTestHarnessTest harnessTest = new ShaderTestHarnessTest();
            harnessTest.testAllFixturesReachReflected();
            harnessTest.testSolidColorFinalReachesReflected();
            harnessTest.testInvalidStageIsReportedAsFailure();
            System.out.println(">>> PASS: ShaderTestHarnessTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: ShaderTestHarnessTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 18. Phase 2: runtime rendering-state layer (replaces silent no-ops)
        try {
            System.out.println("\n[18/18] Running RenderingStateTest...");
            RenderingStateTest stateTest = new RenderingStateTest();
            stateTest.testUniformUploadsAreStored();
            stateTest.testMatrixUploadReadsAbsoluteIndex();
            stateTest.testTextureAndImageBindings();
            stateTest.testSamplerParametersAccumulate();
            stateTest.testFramebufferAttachmentAndCompleteness();
            stateTest.testDrawAndReadBuffers();
            stateTest.testBufferBaseAndUniformBlockBinding();
            stateTest.testShaderStorageBarrierTranslation();
            stateTest.testAllBarrierMapsToAllCommands();
            stateTest.testUnknownBarrierIsConservative();
            stateTest.testDispatchValidationAndQueue();
            stateTest.testMemoryBarrierIsQueuedThenDrained();
            stateTest.testShadowProjectionActivationAndRestore();
            System.out.println(">>> PASS: RenderingStateTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: RenderingStateTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 19. Phase 4: render-target / texture resource layer
        try {
            System.out.println("\n[19/19] Running RenderTargetResourceTest...");
            RenderTargetResourceTest resourceTest = new RenderTargetResourceTest();
            resourceTest.testFormatClassification();
            resourceTest.testSamplerPolicyMapping();
            resourceTest.testSpecRejectsKindMismatch();
            resourceTest.testSpecRejectsInvalidDimensions();
            resourceTest.testSpecSizeBudget();
            resourceTest.testLayoutTracking();
            resourceTest.testProducerConsumerRoles();
            resourceTest.testStandardTargetSet();
            resourceTest.testResizeLeavesFixedShadowMapUntouched();
            resourceTest.testResizeResetsLayout();
            System.out.println(">>> PASS: RenderTargetResourceTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: RenderTargetResourceTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 20. Phase 5: frame/pass uniform state + reflection-driven UBO packing
        try {
            System.out.println("\n[20/20] Running FramePassStateTest...");
            FramePassStateTest frameTest = new FramePassStateTest();
            frameTest.testBeginFrameTracksPreviousCameraPosition();
            frameTest.testFrameStateScreenClipAndFog();
            frameTest.testPassStateSnapshotPreservesPreviousMatrices();
            frameTest.testUniformBlockPackerWritesAtReflectedOffsets();
            frameTest.testUniformBlockPackerReportsMissingMembers();
            System.out.println(">>> PASS: FramePassStateTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: FramePassStateTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 21. Phase 11: synchronization + image layout barriers
        try {
            System.out.println("\n[21/21] Running SynchronizationTest...");
            SynchronizationTest syncTest = new SynchronizationTest();
            syncTest.testUsageToLayoutMapping();
            syncTest.testStageAndAccessMasks();
            syncTest.testBarriersGeneratedForWriteThenSample();
            syncTest.testInitialBarrierUsesUndefinedLayout();
            syncTest.testResourceStateTrackerAppliesBarriers();
            System.out.println(">>> PASS: SynchronizationTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: SynchronizationTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 22. Phase 10: descriptor-set layout generation + validation
        try {
            System.out.println("\n[22/22] Running DescriptorSetLayoutTest...");
            DescriptorSetLayoutTest descriptorTest = new DescriptorSetLayoutTest();
            descriptorTest.testPlanMapsBlocksToFrameSetAndSamplersToPassSet();
            descriptorTest.testArrayedSamplerKeepsCount();
            descriptorTest.testGroupingBySet();
            descriptorTest.testPlanValidatesAgainstItself();
            descriptorTest.testPlanRejectsWrongDescriptorType();
            System.out.println(">>> PASS: DescriptorSetLayoutTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: DescriptorSetLayoutTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 23. GPU phases: shadow/gbuffer/composite/presentation config + planning
        try {
            System.out.println("\n[23/23] Running GpuPhaseConfigTest...");
            GpuPhaseConfigTest gpuTest = new GpuPhaseConfigTest();
            gpuTest.testShadowProjectionPlacesCameraAndMapsCenter();
            gpuTest.testShadowConfigDefaultsAndValidation();
            gpuTest.testShadowRendererActivatesAndRestoresProjection();
            gpuTest.testGbufferConfigOpaqueAndTranslucent();
            gpuTest.testCompositeChainPlannerAlternatesPingPong();
            gpuTest.testCompositeChainBuildsCompilableGraph();
            gpuTest.testCompositeChainRejectsEmpty();
            gpuTest.testResizeCoordinatorLeavesFixedTargets();
            gpuTest.testPresentationTargetFallback();
            gpuTest.testPassBindingFactories();
            gpuTest.testBarrierGroupingByPass();
            System.out.println(">>> PASS: GpuPhaseConfigTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: GpuPhaseConfigTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 24. Phases 13/14/16: translation pipeline + compatibility report
        try {
            System.out.println("\n[24/24] Running TranslationPipelineAndReportTest...");
            TranslationPipelineAndReportTest reportTest = new TranslationPipelineAndReportTest();
            reportTest.testPipelineSucceedsAndKeepsArtifacts();
            reportTest.testPipelineReportsFailureAtSpecificStage();
            reportTest.testReportWithoutCatalogIsNotUsed();
            reportTest.testReportClassifiesDiscoveredFeatures();
            System.out.println(">>> PASS: TranslationPipelineAndReportTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: TranslationPipelineAndReportTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 25. VulkanMod shader-converter type normalization (bool -> int)
        try {
            System.out.println("\n[25/26] Running VulkanModTypeNormalizerTest...");
            VulkanModTypeNormalizerTest normalizerTest = new VulkanModTypeNormalizerTest();
            normalizerTest.testBoolNormalizesToInt();
            normalizerTest.testAdmittedTypesAreUnchanged();
            normalizerTest.testNullAndSubstringsAreSafe();
            System.out.println(">>> PASS: VulkanModTypeNormalizerTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: VulkanModTypeNormalizerTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 26. Architecture Report and Frame Coordinator
        try {
            System.out.println("\n[26/26] Running ArchitectureReportAndFrameCoordinatorTest...");
            ArchitectureReportAndFrameCoordinatorTest archTest = new ArchitectureReportAndFrameCoordinatorTest();
            archTest.testArchitectureReportProbeEvaluation();
            archTest.testArchitectureReportCaptureDoesNotThrow();
            archTest.testFrameCoordinatorLifecycle();
            System.out.println(">>> PASS: ArchitectureReportAndFrameCoordinatorTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: ArchitectureReportAndFrameCoordinatorTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 27. Phase 1: structured diagnostics (record identity + per-frame dedup)
        try {
            System.out.println("\n[27/28] Running DiagnosticManagerTest...");
            DiagnosticManagerTest diagnosticsTest = new DiagnosticManagerTest();
            diagnosticsTest.clearHistory();
            diagnosticsTest.testRecordCarriesFullIdentity();
            diagnosticsTest.clearHistory();
            diagnosticsTest.testRecordIdsAreUnique();
            diagnosticsTest.clearHistory();
            diagnosticsTest.testIdenticalFailuresAreLoggedOnce();
            diagnosticsTest.clearHistory();
            diagnosticsTest.testChangedSourceLogsAgain();
            diagnosticsTest.clearHistory();
            diagnosticsTest.testCompileAndLinkErrorsAreTracked();
            diagnosticsTest.clearHistory();
            diagnosticsTest.testLegacyLogStillRecords();
            diagnosticsTest.testHashSourceIsStable();
            System.out.println(">>> PASS: DiagnosticManagerTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: DiagnosticManagerTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 28. Phase 1: diagnostic harness (gated dumps, debug-only test draw, fixtures, regressions)
        try {
            System.out.println("\n[28/28] Running DiagnosticHarnessTest...");
            DiagnosticHarnessTest harnessDiagnosticsTest = new DiagnosticHarnessTest();
            harnessDiagnosticsTest.testDumpAndTraceFlagsDefaultOff();
            harnessDiagnosticsTest.testDebugDrawDefaultsOff();
            harnessDiagnosticsTest.testConfigManagerIsHeadlessSafe();
            harnessDiagnosticsTest.testLegacyTestRendererStaysDebugOnly();
            harnessDiagnosticsTest.testDumpsAreNoOpsByDefault();
            harnessDiagnosticsTest.testDumpsWriteWhenEnabled();
            harnessDiagnosticsTest.testAlphaBlendFixtureReachesReflected();
            harnessDiagnosticsTest.testDepthFixtureReachesReflected();
            harnessDiagnosticsTest.testMismatchedInterfaceIsRejectedBeforePipeline();
            harnessDiagnosticsTest.testRegressionsCarryFixedCaptureParameters();
            System.out.println(">>> PASS: DiagnosticHarnessTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: DiagnosticHarnessTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 29. Phase 2: Iris source boundary (provenance, generations, idempotent translation)
        try {
            System.out.println("\n[29/29] Running SourceProvenanceTest...");
            SourceProvenanceTest provenanceTest = new SourceProvenanceTest();
            provenanceTest.testCaptureRecordsFullProvenance();
            provenanceTest.testStageKeyNormalization();
            provenanceTest.testRepeatCaptureOfIdenticalInputIsStable();
            provenanceTest.testChangedInputReplacesUnit();
            provenanceTest.testTranslationLinksToInputHashStageAndGeneration();
            provenanceTest.testDirectCompilerUseStaysTraceable();
            provenanceTest.testGenerationBumpInvalidatesOldUnits();
            provenanceTest.testRetranslationIsStableAcrossAllFixtures();
            provenanceTest.testTranslatedSourceIsDetectable();
            System.out.println(">>> PASS: SourceProvenanceTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: SourceProvenanceTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 30. Phase 3: token lexer, bounded stream and legacy-keyword pass goldens
        try {
            System.out.println("\n[30/30] Running GlslLexerTest + TokenStreamTest + LegacyKeywordPassTest...");
            GlslLexerTest lexerTest = new GlslLexerTest();
            lexerTest.testTokenKinds();
            lexerTest.testNumbersStringsAndSymbols();
            lexerTest.testBlockCommentIsOneToken();
            lexerTest.testDirectiveWithContinuationIsOneToken();
            lexerTest.testLineAndColumnMetadata();
            lexerTest.testEmitRoundTripsAllFixtures();
            lexerTest.testUnterminatedBlockCommentFails();
            lexerTest.testUnterminatedStringFails();
            lexerTest.testNullSourceFails();
            TokenStreamTest streamTest = new TokenStreamTest();
            streamTest.testMatchingParensWithNesting();
            streamTest.testMatchingBracket();
            streamTest.testUnbalancedDelimiterFails();
            streamTest.testWrongOpenerFails();
            streamTest.testStatementEnd();
            streamTest.testCommentsAndDirectivesAreNotCode();
            LegacyKeywordPassTest legacyTest = new LegacyKeywordPassTest();
            legacyTest.testAttributeBecomesIn();
            legacyTest.testVaryingDirectionIsStageSpecific();
            legacyTest.testQualifiersAndWhitespacePreserved();
            legacyTest.testBlockCommentIsNeverRewritten();
            legacyTest.testLineCommentIsNeverRewritten();
            legacyTest.testDirectivesAndMacroBodiesAreNeverRewritten();
            legacyTest.testMacroContinuationIsNeverRewritten();
            legacyTest.testUnrelatedConstructsByteIdentical();
            legacyTest.testMultiLineDeclarationConverts();
            legacyTest.testRepeatedIdentifiersAllConvert();
            legacyTest.testSimilarIdentifiersUntouched();
            legacyTest.testNullPassthrough();
            legacyTest.testPassIsDeterministicAndIdempotent();
            legacyTest.testSourceMapIsIdentityForKeywordSwap();
            legacyTest.testRegistryRunsPassesInOrderAndToggles();
            System.out.println(">>> PASS: GlslLexerTest + TokenStreamTest + LegacyKeywordPassTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: GlslLexerTest + TokenStreamTest + LegacyKeywordPassTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 31. Phase 4: honest compile results, stage gates, validation, reflection
        try {
            System.out.println("\n[31/31] Running ShaderCompileResultTest...");
            ShaderCompileResultTest compileResultTest = new ShaderCompileResultTest();
            compileResultTest.testStageMappingForEveryShaderType();
            compileResultTest.testIntStageMappingPreserved();
            compileResultTest.testUnsupportedKindRejectedWithoutBytecode();
            compileResultTest.testInvalidGlslYieldsFailedStatusLogAndNoBytecode();
            compileResultTest.testLegacyThrowingFormPreserved();
            compileResultTest.testValidFixtureSucceedsWithValidationAndReflection();
            compileResultTest.testRejectedStageCompilesToContainedHandle();
            compileResultTest.testValidatorRejectsBadVersion();
            compileResultTest.testValidatorRejectsBadSchema();
            compileResultTest.testValidatorRequiresEntryPoint();
            compileResultTest.testValidatorAcceptsRealFixtureModule();
            compileResultTest.testProfileDigestStable();
            System.out.println(">>> PASS: ShaderCompileResultTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: ShaderCompileResultTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 32. Phase 5: virtual shader/program status plus link validation
        try {
            System.out.println("\n[32/32] Running VirtualGlStatusTest...");
            VirtualGlStatusTest statusTest = new VirtualGlStatusTest();
            statusTest.testCompiledShaderReportsSuccess();
            statusTest.testFailedCompileReportsFailurePlusLog();
            statusTest.testLinkedProgramReportsLinkPlusCounts();
            statusTest.testFailedLinkReportsFailureWithNamesAndTypes();
            statusTest.testProgramWithMissingStageFailsLink();
            statusTest.testUniformLookupsAreReflectionBacked();
            statusTest.testActiveUniformDiscoveryMatchesLocations();
            statusTest.testUniformLocationsAreDeterministic();
            statusTest.testAttributeLookupsAreReflectionBacked();
            statusTest.testDeletedIdsFailPredictably();
            statusTest.testDanglingProgramIsInvalidated();
            statusTest.testUnknownIdsKeepLegacyAnswers();
            System.out.println(">>> PASS: VirtualGlStatusTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: VirtualGlStatusTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 33. Phase 6: pipeline cache key coverage plus creation guards
        try {
            System.out.println("\n[33/33] Running PipelineKeyTest...");
            PipelineKeyTest keyTest = new PipelineKeyTest();
            keyTest.testIdenticalParamsShareKey();
            keyTest.testSamplesLayoutAndFlagsDistinguishKeys();
            keyTest.testInterfaceFingerprintTracksReflection();
            keyTest.testStateTrackerKeyCarriesNewFields();
            keyTest.testCreationGuardsFireBeforeNativeCalls();
            System.out.println(">>> PASS: PipelineKeyTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: PipelineKeyTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 34. Phase 7: device generations, cache safety, idempotent lifecycle
        try {
            System.out.println("\n[34/34] Running DeviceOwnershipTest...");
            DeviceOwnershipTest ownershipTest = new DeviceOwnershipTest();
            ownershipTest.testDeviceGenerationAnchorsAndBumps();
            ownershipTest.testRecreatedDeviceInvalidatesHandles();
            ownershipTest.testCacheBlobValidation();
            ownershipTest.testAtomicWriteRoundTrip();
            ownershipTest.testReloadCyclesStayBoundedWithoutIdReuse();
            ownershipTest.testCleanupIsIdempotentHeadless();
            System.out.println(">>> PASS: DeviceOwnershipTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: DeviceOwnershipTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 35. Phase 8: target descriptors, framebuffer truth, viewer, clear tracking
        try {
            System.out.println("\n[35/35] Running TargetDescriptorTest + FramebufferValidatorTest + TargetViewerTest...");
            TargetDescriptorTest targetDescriptorTest = new TargetDescriptorTest();
            targetDescriptorTest.testColorAndDepthFactories();
            targetDescriptorTest.testValidationRejectsBadDescriptors();
            targetDescriptorTest.testSizeScalingResolves();
            targetDescriptorTest.testToSpecBridgesToAllocation();
            targetDescriptorTest.testClearColorDefensiveCopy();
            FramebufferValidatorTest framebufferTest = new FramebufferValidatorTest();
            framebufferTest.testEmptyFramebufferIsMissingAttachments();
            framebufferTest.testNullAttachmentsAreMissing();
            framebufferTest.testZeroTextureAttachmentIsBroken();
            framebufferTest.testBoundAttachmentsAreComplete();
            TargetViewerTest viewerTest = new TargetViewerTest();
            viewerTest.testStandardSetDescribes();
            viewerTest.testEmptyRegistryDescribes();
            viewerTest.testSummaryCountsPendingClears();
            viewerTest.testClearTrackingAcrossLifecycle();
            viewerTest.testFormatConversionHasNoSilentDowncast();
            System.out.println(">>> PASS: TargetDescriptorTest + FramebufferValidatorTest + TargetViewerTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: TargetDescriptorTest + FramebufferValidatorTest + TargetViewerTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 36. Phase 9: transform ledger plus matrix-upload order
        try {
            System.out.println("\n[36/36] Running TransformLedgerTest + UniformBridgeTest...");
            TransformLedgerTest ledgerTest = new TransformLedgerTest();
            ledgerTest.testDepthRangeMapping();
            ledgerTest.testFrontFaceMirrorsForYFlip();
            ledgerTest.testSingleFlipConfigured();
            ledgerTest.testRawPipelineDefaultMirrorsWinding();
            ledgerTest.testBlendIsStraightAlpha();
            ledgerTest.testColorOwnershipNeverDoubles();
            ledgerTest.testBrightOutlineTriageOrder();
            ledgerTest.testLedgerDescribes();
            UniformBridgeTest bridgeTest = new UniformBridgeTest();
            bridgeTest.testProjectionArrivesDepthCorrected();
            bridgeTest.testDepthBasisVectorsMapToVulkanRange();
            bridgeTest.testUploadLayoutIsColumnMajorStd140();
            bridgeTest.testShortBufferIsNeverOverwritten();
            bridgeTest.testNullInputsAreIgnored();
            System.out.println(">>> PASS: TransformLedgerTest + UniformBridgeTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: TransformLedgerTest + UniformBridgeTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 37. Phase 10: standard uniforms, isolation/packing, sampler mapping
        try {
            System.out.println("\n[37/37] Running StandardUniformsTest + UniformIsolationTest + SamplerMappingTest...");
            StandardUniformsTest standardTest = new StandardUniformsTest();
            standardTest.testSnapshotCarriesKnownValues();
            standardTest.testSnapshotToleratesNulls();
            standardTest.testCadencesDocumented();
            UniformIsolationTest isolationTest = new UniformIsolationTest();
            isolationTest.testWritesIsolatedByProgram();
            isolationTest.testFrameBoundariesDropStaleValues();
            isolationTest.testStd140Layouts();
            isolationTest.testPlannerPacksFromReflectionWithStrides();
            isolationTest.testPlannerIsolatesProgramsAndReportsMissing();
            SamplerMappingTest samplerTest = new SamplerMappingTest();
            samplerTest.testUnitPolicy();
            samplerTest.testOverBudgetProgramRefusedWithLimit();
            samplerTest.testBindingIdentityAcrossReuse();
            samplerTest.testCompareSamplersExist();
            samplerTest.testPbrInvalidation();
            System.out.println(">>> PASS: StandardUniformsTest + UniformIsolationTest + SamplerMappingTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: StandardUniformsTest + UniformIsolationTest + SamplerMappingTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 38. Phase 11: pass gates, scheduling, traces, runtime reports, frame pinning
        try {
            System.out.println("\n[38/38] Running PassNodeConfigTest + PassSchedulingTest + CoordinatorPinTest...");
            PassNodeConfigTest passNodeTest = new PassNodeConfigTest();
            passNodeTest.testDefaults();
            passNodeTest.testBuilderConfig();
            passNodeTest.testBadViewportRejected();
            passNodeTest.testRunAfterOrdersPasses();
            passNodeTest.testRunAfterUnknownPassFails();
            passNodeTest.testContradictoryRunAfterIsACycle();
            passNodeTest.testKindSupportUnchanged();
            PassSchedulingTest schedulingTest = new PassSchedulingTest();
            schedulingTest.testRequiredGatesFollowGroups();
            schedulingTest.testImplementedTableIsHonest();
            schedulingTest.testExecutionRecordOverwriteRules();
            schedulingTest.testFrameTraceValidation();
            schedulingTest.testRuntimeReportListsOutcomes();
            schedulingTest.testRuntimeReportNullSafe();
            CoordinatorPinTest pinTest = new CoordinatorPinTest();
            pinTest.testPinDropAndRepin();
            pinTest.testDropClearsExecutionRecord();
            pinTest.testInactiveCoordinatorStaysInactive();
            pinTest.testTraceStartsClean();
            System.out.println(">>> PASS: PassNodeConfigTest + PassSchedulingTest + CoordinatorPinTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: PassNodeConfigTest + PassSchedulingTest + CoordinatorPinTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 39. Phase 12: capability gates, caches, worker generations, hook policy
        try {
            System.out.println("\n[39/39] Running CapabilityGateTest + CachingTest + TranslationWorkerTest + RenderHookGuardTest...");
            CapabilityGateTest gateTest = new CapabilityGateTest();
            gateTest.testVanillaAccepted();
            gateTest.testCleanPackAccepted();
            gateTest.testGeometryPackRejectedWithProgram();
            gateTest.testTessellationPackRejected();
            gateTest.testPackWithoutExecutablePathRejected();
            gateTest.testComputePresenceIsPartialNotFatal();
            CachingTest cachingTest = new CachingTest();
            cachingTest.testTranslationKeyAndHit();
            cachingTest.testTranslationCacheBoundedAndBlankSafe();
            cachingTest.testCompileKeyChangesWithInputs();
            cachingTest.testFailuresNeverCached();
            cachingTest.testCompileHitReturnsIdenticalBytes();
            TranslationWorkerTest workerTest = new TranslationWorkerTest();
            workerTest.testValueReturnedForCurrentGeneration();
            workerTest.testStaleTaskYieldsNothingAndCancels();
            workerTest.testFailingTaskYieldsNothing();
            workerTest.testPurgeDropsOnlyStaleQueuedTasks();
            RenderHookGuardTest hookTest = new RenderHookGuardTest();
            hookTest.testNormalPathRuns();
            hookTest.testExpectedFailureRecordedOnce();
            hookTest.testFatalFailureFallsBackWithGuidance();
            System.out.println(">>> PASS: CapabilityGateTest + CachingTest + TranslationWorkerTest + RenderHookGuardTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: CapabilityGateTest + CachingTest + TranslationWorkerTest + RenderHookGuardTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 40. Phase 13: release qualification and published feature table
        try {
            System.out.println("\n[40/40] Running FeatureTableTest + ReleaseQualificationTest...");
            FeatureTableTest featureTableTest = new FeatureTableTest();
            featureTableTest.testVersionsPinned();
            featureTableTest.testEveryFamilyRepresented();
            featureTableTest.testNoBlanketCompatibilityClaim();
            featureTableTest.testGameRunEvidenceTracked();
            ReleaseQualificationTest qualificationTest = new ReleaseQualificationTest();
            qualificationTest.testIdenticalReloadHitsCachesWithIdenticalBytes();
            qualificationTest.testReloadTimingsRecorded();
            qualificationTest.testGenerationChangeInvalidatesRepeatably();
            qualificationTest.testDeletedCycleFailsPredictably();
            qualificationTest.testResizePreservesTargetSet();
            qualificationTest.testNegativePathsStayClean();
            System.out.println(">>> PASS: FeatureTableTest + ReleaseQualificationTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: FeatureTableTest + ReleaseQualificationTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 41. GUI plan Phase 2: toggle contract plus stable categories
        try {
            System.out.println("\n[41/41] Running ToggleStateTest...");
            ToggleStateTest toggleTest = new ToggleStateTest();
            toggleTest.testLabelAndFillDeriveFromValue();
            toggleTest.testSuccessfulApplyTakesRequested();
            toggleTest.testFailedApplyRollsBack();
            toggleTest.testCategoriesStable();
            System.out.println(">>> PASS: ToggleStateTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: ToggleStateTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 42. GUI plan Phase 3: config persistence, migration, recovery
        try {
            System.out.println("\n[42/42] Running ConfigPersistenceTest...");
            ConfigPersistenceTest configTest = new ConfigPersistenceTest();
            configTest.testDefaultsAndValidation();
            configTest.testRepairFillsNulls();
            configTest.testSaveReloadRoundTrip();
            configTest.testEnumStoredAsStableIdentifier();
            configTest.testVersionOneFileMigrates();
            configTest.testCorruptFileBackedUpAndReplaced();
            configTest.testHeadlessKeepsMemoryDefaults();
            System.out.println(">>> PASS: ConfigPersistenceTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: ConfigPersistenceTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 43. GUI plan Phase 4: General/Rendering models plus services
        try {
            System.out.println("\n[43/43] Running CategoryModelTest...");
            CategoryModelTest categoryTest = new CategoryModelTest();
            categoryTest.testCapabilitySnapshot();
            categoryTest.testStartupValidationStructure();
            categoryTest.testSettingsApplyBoundary();
            categoryTest.testLogDiagnosticsToggle();
            categoryTest.testCompatServiceRunsRealChecks();
            categoryTest.testCompatServiceAsyncAndCancel();
            categoryTest.testGeneralModelRows();
            categoryTest.testRenderingModelRows();
            categoryTest.testModelComposition();
            System.out.println(">>> PASS: CategoryModelTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: CategoryModelTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 44. GUI plan Phase 5: live pack discovery plus browser model
        try {
            System.out.println("\n[44/44] Running ShaderPackServiceTest + PackBrowserModelTest...");
            ShaderPackServiceTest packServiceTest = new ShaderPackServiceTest();
            packServiceTest.testTwoInstalledPacks();
            packServiceTest.testNoExtraPacks();
            packServiceTest.testInvalidFilesWarnWithoutCards();
            packServiceTest.testFileLifecycle();
            packServiceTest.testEmptyAndMissingFolders();
            packServiceTest.testAsyncScanInstalls();
            packServiceTest.testTruncatedZipIsUnreadable();
            PackBrowserModelTest browserTest = new PackBrowserModelTest();
            browserTest.testSmokeTestPassesAndFails();
            browserTest.testModelSelectTestAndStale();
            browserTest.testSelectionClearedWhenFileVanishes();
            browserTest.testHistoryRetainsOnlyDiscovered();
            browserTest.testCategoryRowsPresent();
            System.out.println(">>> PASS: ShaderPackServiceTest + PackBrowserModelTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: ShaderPackServiceTest + PackBrowserModelTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 45. GUI plan Phase 6: staged pack validation workflow
        try {
            System.out.println("\n[45/45] Running PackValidationTest...");
            PackValidationTest packValidationTest = new PackValidationTest();
            packValidationTest.testFullPackPassesAllStages();
            packValidationTest.testBrokenPairFailsCompilationWithCause();
            packValidationTest.testMismatchedInterfaceFailsResources();
            packValidationTest.testCompilingWithoutPostPathIsPartial();
            packValidationTest.testUnknownCompositeSamplerIsPartial();
            packValidationTest.testMissingPairAndNullArchiveFailScan();
            packValidationTest.testCancelAndAsync();
            packValidationTest.testModelRunsFullValidation();
            System.out.println(">>> PASS: PackValidationTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: PackValidationTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 46. GUI plan Phase 7: sanitized reports, export, clipboard fallback
        try {
            System.out.println("\n[46/46] Running DiagnosticReportTest...");
            DiagnosticReportTest reportTest = new DiagnosticReportTest();
            reportTest.testSanitizeRedactsPersonalPaths();
            reportTest.testCollectHasAllSections();
            reportTest.testLogExcerptBounded();
            reportTest.testConciseSummaryBounded();
            reportTest.testExportRoundTrip();
            reportTest.testExportFailureActionable();
            reportTest.testDiagnosticsModelWired();
            reportTest.testDiagnosticsModelReal();
            System.out.println(">>> PASS: DiagnosticReportTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: DiagnosticReportTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 47. Phase A: Iris-pack #include resolver (absolute/relative/case/miss/cycle/escape)
        try {
            System.out.println("\n[47/54] Running IncludeResolverTest...");
            IncludeResolverTest includeTest = new IncludeResolverTest();
            includeTest.testAbsoluteIncludeResolvesUnderShadersRoot();
            includeTest.testRelativeIncludeResolvesNextToIncluder();
            includeTest.testAngleBracketFormResolves();
            includeTest.testLeadingSlashRunsAreStripped();
            includeTest.testCaseInsensitiveFallback();
            includeTest.testMissingIncludeEmitsNamedError();
            includeTest.testCyclicIncludeEmitsNamedError();
            includeTest.testEscapeAttemptIsContained();
            includeTest.testDepthLimitTerminates();
            includeTest.testRelativeIncludeWithUnknownIncluderMisses();
            includeTest.testLineMarkersMapToPackFiles();
            includeTest.testCommentedIncludeIsIgnored();
            includeTest.testMissingPackIsStructuredMiss();
            includeTest.testNoIncludesPassesThroughIdentical();
            includeTest.testNestedVersionIsDropped();
            includeTest.testMemoPerGenerationAndEntry();
            includeTest.testFixtureZipWithProgramIncludes();
            System.out.println(">>> PASS: IncludeResolverTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: IncludeResolverTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 48. Phase B: legacy-GLSL mappings + program-together translation goldens.
        try {
            System.out.println("\n[48/54] Running LegacyGlslTest + ProgramTranslatorTest + BridgeUnionBlockTest + FullscreenDepthRootsTest...");
            LegacyGlslTest legacyGlslTest = new LegacyGlslTest();
            legacyGlslTest.testTexture2DFamilyMapsToCoreCallees();
            legacyGlslTest.testShadowLookupsAreWrappedNotRenamed();
            legacyGlslTest.testVolumeLookupsModernize();
            legacyGlslTest.testDepthBuiltinsPassThrough();
            legacyGlslTest.testRewriteIsIdempotent();
            legacyGlslTest.testCommentsStringsAndDirectivesAreNeverRewritten();
            legacyGlslTest.testFragDataIndicesBecomeIndexedOutputs();
            legacyGlslTest.testFragColorBecomesIndexedOutputZero();
            legacyGlslTest.testNonLiteralFragDataSubscriptIsLeftUntouched();
            legacyGlslTest.testFragOutputDeclarations();
            legacyGlslTest.testCollectFragDataIndices();
            legacyGlslTest.testTextureMatrixMapsToUniformArray();
            legacyGlslTest.testFtransformExpandsToExplicitMatrices();
            legacyGlslTest.testFtransformWithArgumentsIsLeftUntouched();
            legacyGlslTest.testColorAndNormalMapToAttributes();
            legacyGlslTest.testMatrixFamilyMapsToUniforms();
            legacyGlslTest.testNullInputIsHandled();
            legacyGlslTest.testLocationSlotsAccountForRank();
            legacyGlslTest.testMatrixVaryingSplitsIntoColumnLeaves();
            legacyGlslTest.testArrayVaryingSplitsIntoElementLeaves();
            legacyGlslTest.testStructVaryingSplitsPerMember();
            legacyGlslTest.testDenseLocationsAccumulateAcrossMixedLeaves();
            legacyGlslTest.testUnionVaryingHelpers();
            legacyGlslTest.testShadow2DProjLodIsWrapped();
            legacyGlslTest.testPrecisionStatementsAreStripped();
            legacyGlslTest.testPrecisionDirectivesAreNeverTouched();
            legacyGlslTest.testNonConstantConstIsDemoted();
            legacyGlslTest.testConstantConstChainIsKept();
            legacyGlslTest.testConstParamsAndDirectivesAreNeverDemoted();
            legacyGlslTest.testTranslateLegacyBuiltinsStripsAndDemotesTogether();
            legacyGlslTest.testParseStructMembers();
            legacyGlslTest.testSplitLeafAndBaseLocationsStayDense();
            ProgramTranslatorTest programTranslatorTest = new ProgramTranslatorTest();
            programTranslatorTest.testTerrainPairTranslatesAndCompiles();
            programTranslatorTest.testEntityPairTranslatesAndCompiles();
            programTranslatorTest.testCloudPairTranslatesAndCompiles();
            programTranslatorTest.testSkyPairTranslatesAndCompiles();
            programTranslatorTest.testOverlappingLocationsDoNotCollideAfterTranslation();
            programTranslatorTest.testGenerationChangeInvalidatesProgramCache();
            programTranslatorTest.testStripHelpersAndNullSafety();
            programTranslatorTest.testShadowProjLodPrecisionAndConstDemotionPairCompiles();
            programTranslatorTest.testMatArrayStructVaryingPairSplitsEndToEnd();
            programTranslatorTest.testOneSidedVaryingSurvivesLink();
            programTranslatorTest.testBoolVaryingConvertsToIntEndToEnd();
            BridgeUnionBlockTest bridgeUnionBlockTest = new BridgeUnionBlockTest();
            bridgeUnionBlockTest.setUp();
            bridgeUnionBlockTest.testDriverPathSharesIdenticalUnionBlock();
            bridgeUnionBlockTest.setUp();
            bridgeUnionBlockTest.testSharedBindingHelperContract();
            LegacyAttribLinkPathTest linkPathTest = new LegacyAttribLinkPathTest();
            linkPathTest.clearCaches();
            linkPathTest.testLegacyAttribsSelectARealVertexFormat();
            linkPathTest.clearCaches();
            linkPathTest.testLegacyLinkPathInjectsAndCompiles();
            linkPathTest.testLegacyAliasMapCoversAllFixedFunctionAttribs();
            FullscreenDepthRootsTest fullscreenDepthTest = new FullscreenDepthRootsTest();
            fullscreenDepthTest.testForwardZDepthBuiltinsPassThrough();
            fullscreenDepthTest.testForwardZClearAndCompareAgreement();
            fullscreenDepthTest.testFullscreenCompositePairTranslatesAndLinks();
            fullscreenDepthTest.testProceduralFullscreenInputsSelectSmallestFormat();
            fullscreenDepthTest.testEntityAndSkyRootsSelectCoveringFormats();
            fullscreenDepthTest.testInverseMatricesStayCompilerReported();
            System.out.println(">>> PASS: LegacyGlslTest + ProgramTranslatorTest + BridgeUnionBlockTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: LegacyGlslTest + ProgramTranslatorTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 49. Phase F: GUI close-out (toggle contract, scan states, rescan, mods refusal,
        // sanitize/bounds/export, Advanced gates, ModMenu + Compatibility wiring)
        try {
            System.out.println("\n[49/54] Running PhaseFGuiCloseoutTest...");
            PhaseFGuiCloseoutTest phaseFTest = new PhaseFGuiCloseoutTest();
            phaseFTest.testToggleColorAndLabelChangeTogether();
            phaseFTest.testEveryToggleRowHasTooltipAndRoundTrips();
            phaseFTest.testScanStateMatrix();
            phaseFTest.testAsyncScanInstallsOnModel();
            phaseFTest.testRescanReconcilesAddRemoveReplaceDuplicateCorrupt();
            phaseFTest.testModsFolderAndJarsNeverBecomePacks();
            phaseFTest.testSanitizeRedactsUsernamesAndProfiles();
            phaseFTest.testReportBoundsAndExportRoundTrip();
            phaseFTest.testAdvancedCloseoutGates();
            phaseFTest.testModMenuAndCompatibilityWiringIntact();
            System.out.println(">>> PASS: PhaseFGuiCloseoutTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: PhaseFGuiCloseoutTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 50. Phase C: compile-path hardening (bounded stack/slots, oversized rejection,
        // versioned module cache + disk persistence, warmup-before-draw, stage rejection)
        try {
            System.out.println("\n[50/54] Running CompileHardeningTest...");
            CompileHardeningTest hardeningTest = new CompileHardeningTest();
            hardeningTest.testOversizedSourceRejectedWithReason();
            hardeningTest.testConcurrentCompilesStayCapped();
            hardeningTest.testCacheColdThenWarm();
            hardeningTest.testVersionedKeySeparatesStamps();
            hardeningTest.testDefinesChangeCompilationKey();
            hardeningTest.testDiskPersistenceRoundTripAndOptOut();
            hardeningTest.testTranslationKeyCoversProgramAndDefines();
            hardeningTest.testWarmupBeforeDraw();
            hardeningTest.testStageRejectionCoversAllUnsupported();
            hardeningTest.testCachedViewsAreIndependentDuplicates();
            System.out.println(">>> PASS: CompileHardeningTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: CompileHardeningTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 51. Phase E: chain-plan replay, substitute-final labeling, fallback honesty
        try {
            System.out.println("\n[51/54] Running PhaseEChainFallbackTest...");
            PhaseEChainFallbackTest phaseETest = new PhaseEChainFallbackTest();
            phaseETest.testPlanReplayDeterministic();
            phaseETest.testSubstituteFinalLabeling();
            phaseETest.testFallbackReasonPropagationToStatus();
            phaseETest.testComplementaryShapedPostChainBuildsPipeline();
            System.out.println(">>> PASS: PhaseEChainFallbackTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: PhaseEChainFallbackTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 52. Phase D: Sodium/VulkanMod terrain door (mesh contract, compile/render
        // decisions, pack targets, shadow lag/cull, per-frame uniforms, fallback reasons)
        try {
            System.out.println("\n[52/54] Running PhaseDTerrainDoorTest...");
            PhaseDTerrainDoorTest phaseDTest = new PhaseDTerrainDoorTest();
            phaseDTest.testVertexContractLayout();
            phaseDTest.testVertexContractSettlesOnce();
            phaseDTest.testVertexContractBlockEvidence();
            phaseDTest.testDoorPassMapping();
            phaseDTest.testDoorHandedPassResolution();
            phaseDTest.testDoorCompileNeedsLinkedPipeline();
            phaseDTest.testDoorDescriptorSwapValidation();
            phaseDTest.testDoorRenderLenientGate();
            phaseDTest.testFallbackReasonNamesEverything();
            phaseDTest.testBridgeTerrainRegistrationIsHonestHeadless();
            phaseDTest.testBridgeTerrainVertexHelpers();
            phaseDTest.testBridgeTerrainRenderHelpers();
            phaseDTest.testPackPassMirror();
            phaseDTest.testDescriptorSwapDecisions();
            phaseDTest.testPackTargetsCreateAndValidate();
            phaseDTest.testPackTargetsResizeAndMip();
            phaseDTest.testPackTargetsDimensionMismatchReported();
            phaseDTest.testPackTargetFramebufferValidation();
            phaseDTest.testShadowFrameLagIsOneFrameLate();
            phaseDTest.testShadowCulling();
            phaseDTest.testCustomExpressionEvaluator();
            phaseDTest.testSnapshotOnceThenPerPassWrite();
            phaseDTest.testPassWriteReportsShortAndMissing();
            phaseDTest.testUniformValueKindsPublish();
            phaseDTest.testTerrainGateClosedWithoutLinkNamesMissingCondition();
            phaseDTest.testTerrainGateOpensOnLinkPlusValidDescriptors();
            phaseDTest.testTerrainGateLiveWiringStaysHonestHeadless();
            System.out.println(">>> PASS: PhaseDTerrainDoorTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: PhaseDTerrainDoorTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 53. Phase 1: production readiness state machine and truthful pass gating
        try {
            System.out.println("\n[53/54] Running ReadinessStateMachineTest...");
            ReadinessStateMachineTest readinessTest = new ReadinessStateMachineTest();
            readinessTest.setUp();
            readinessTest.testInitialStateIsNoPack();
            readinessTest.setUp();
            readinessTest.testSequentialValidTransitions();
            readinessTest.setUp();
            readinessTest.testPromotionBlockedByClosedPassGates();
            readinessTest.setUp();
            readinessTest.testPromotionBlockedByUnbackedGpuResources();
            readinessTest.setUp();
            readinessTest.testExplicitFailureTransitionsToRejected();
            readinessTest.setUp();
            readinessTest.testResetReturnsToNoPack();
            readinessTest.setUp();
            readinessTest.testTerrainBlockNamesExactMissingCondition();
            System.out.println(">>> PASS: ReadinessStateMachineTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: ReadinessStateMachineTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 54. Phase 2: render lifecycle stage mapping, hook table, and ordering validator
        try {
            System.out.println("\n[54/55] Running RenderLifecycleTest...");
            RenderLifecycleTest lifecycleTest = new RenderLifecycleTest();
            lifecycleTest.setUp();
            lifecycleTest.testStageOrderValuesAndIndices();
            lifecycleTest.setUp();
            lifecycleTest.testRenderLifecycleTableRegistration();
            lifecycleTest.setUp();
            lifecycleTest.testVulkanModLifecycleAdapterValidOrder();
            lifecycleTest.setUp();
            lifecycleTest.testVulkanModLifecycleAdapterDetectsViolation();
            lifecycleTest.setUp();
            lifecycleTest.testVulkanModLifecycleAdapterTraceHistoryBounds();
            lifecycleTest.setUp();
            lifecycleTest.testTraceOnlyModeDefault();
            System.out.println(">>> PASS: RenderLifecycleTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: RenderLifecycleTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 55. Phase 3: immutable pack execution plan from Iris
        try {
            System.out.println("\n[55/56] Running ProgramExecutionPlanTest...");
            ProgramExecutionPlanTest planTest = new ProgramExecutionPlanTest();
            planTest.testProgramVariantKeyComposition();
            planTest.testTargetSpecWindowScaleAndFixedResolution();
            planTest.testBuildPlanWithFullCatalogAndOrdering();
            planTest.testPerDimensionPlanSeparation();
            planTest.testDisabledProgramOptionExcludesFromPasses();
            planTest.testFallbackAppliedResolution();
            planTest.testResizePreservesPlanStructure();
            System.out.println(">>> PASS: ProgramExecutionPlanTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: ProgramExecutionPlanTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 56. Phase 4: unified GLSL translation and compilation coordinator
        try {
            System.out.println("\n[56/57] Running UnifiedTranslationServiceTest...");
            UnifiedTranslationServiceTest unifiedTest = new UnifiedTranslationServiceTest();
            unifiedTest.setUp();
            unifiedTest.testBufferRawSourceAndRetrieve();
            unifiedTest.setUp();
            unifiedTest.testTranslateProgramUnifiesStagesAndRecompiles();
            unifiedTest.setUp();
            unifiedTest.testTranslateProgramIdempotentCache();
            unifiedTest.setUp();
            unifiedTest.testTranslateProgramWithRegistryFallback();
            unifiedTest.setUp();
            unifiedTest.testTranslateProgramHandlesInvalidSource();
            unifiedTest.setUp();
            unifiedTest.testResetClearsBuffersAndOutcomes();
            unifiedTest.setUp();
            unifiedTest.testUnifiedProgramPassesLinkValidationAndDescriptorRebind();
            System.out.println(">>> PASS: UnifiedTranslationServiceTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: UnifiedTranslationServiceTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 57. Phase 5: typed target, sampler and uniform plans
        try {
            System.out.println("\n[57/61] Running TypedPlansAndDescriptorsTest...");
            TypedPlansAndDescriptorsTest plansTest = new TypedPlansAndDescriptorsTest();
            plansTest.setUp();
            plansTest.testSamplerPlanTotalBindingAndRejection();
            plansTest.setUp();
            plansTest.testTargetPlanAndPingPongSchedule();
            plansTest.setUp();
            plansTest.testUniformPlanStd140Layout();
            plansTest.setUp();
            plansTest.testUniformBlockPackerWritesValidStd140Buffer();
            plansTest.setUp();
            plansTest.testVulkanDescriptorManagerPassValidation();
            System.out.println(">>> PASS: TypedPlansAndDescriptorsTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: TypedPlansAndDescriptorsTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 58. Phase D: Terrain door (mesh contract, compile/render decisions, targets, shadow, uniforms)
        try {
            System.out.println("\n[58/61] Running PhaseDTerrainDoorTest...");
            PhaseDTerrainDoorTest doorTest = new PhaseDTerrainDoorTest();
            doorTest.testVertexContractLayout();
            doorTest.testVertexContractSettlesOnce();
            doorTest.testVertexContractBlockEvidence();
            doorTest.testDoorPassMapping();
            doorTest.testDoorHandedPassResolution();
            doorTest.testDoorCompileNeedsLinkedPipeline();
            doorTest.testDoorDescriptorSwapValidation();
            doorTest.testDoorRenderLenientGate();
            doorTest.testFallbackReasonNamesEverything();
            doorTest.testBridgeTerrainRegistrationIsHonestHeadless();
            doorTest.testBridgeTerrainVertexHelpers();
            doorTest.testBridgeTerrainRenderHelpers();
            doorTest.testPackPassMirror();
            doorTest.testDescriptorSwapDecisions();
            doorTest.testPackTargetsCreateAndValidate();
            doorTest.testPackTargetsResizeAndMip();
            doorTest.testPackTargetsDimensionMismatchReported();
            doorTest.testPackTargetFramebufferValidation();
            doorTest.testShadowFrameLagIsOneFrameLate();
            doorTest.testShadowCulling();
            doorTest.testCustomExpressionEvaluator();
            doorTest.testSnapshotOnceThenPerPassWrite();
            doorTest.testPassWriteReportsShortAndMissing();
            doorTest.testUniformValueKindsPublish();
            doorTest.testTerrainGateClosedWithoutLinkNamesMissingCondition();
            doorTest.testTerrainGateOpensOnLinkPlusValidDescriptors();
            doorTest.testTerrainGateLiveWiringStaysHonestHeadless();
            System.out.println(">>> PASS: PhaseDTerrainDoorTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: PhaseDTerrainDoorTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 59. Phase E: Frame orchestration and fallback honesty
        try {
            System.out.println("\n[59/61] Running PhaseEChainFallbackTest...");
            PhaseEChainFallbackTest chainTest = new PhaseEChainFallbackTest();
            chainTest.testPlanReplayDeterministic();
            chainTest.testSubstituteFinalLabeling();
            chainTest.testFallbackReasonPropagationToStatus();
            chainTest.testComplementaryShapedPostChainBuildsPipeline();
            System.out.println(">>> PASS: PhaseEChainFallbackTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: PhaseEChainFallbackTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 60. Phase F: GUI close-out
        try {
            System.out.println("\n[60/61] Running PhaseFGuiCloseoutTest...");
            PhaseFGuiCloseoutTest guiTest = new PhaseFGuiCloseoutTest();
            guiTest.testToggleColorAndLabelChangeTogether();
            guiTest.testEveryToggleRowHasTooltipAndRoundTrips();
            guiTest.testScanStateMatrix();
            guiTest.testAsyncScanInstallsOnModel();
            guiTest.testRescanReconcilesAddRemoveReplaceDuplicateCorrupt();
            guiTest.testModsFolderAndJarsNeverBecomePacks();
            guiTest.testSanitizeRedactsUsernamesAndProfiles();
            guiTest.testReportBoundsAndExportRoundTrip();
            guiTest.testAdvancedCloseoutGates();
            guiTest.testModMenuAndCompatibilityWiringIntact();
            System.out.println(">>> PASS: PhaseFGuiCloseoutTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: PhaseFGuiCloseoutTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // 61. DH degrade pass: distant-horizons programs skip cleanly without the DH mod
        try {
            System.out.println("\n[61/61] Running DistantHorizonsDegradeTest...");
            DistantHorizonsDegradeTest dhTest = new DistantHorizonsDegradeTest();
            dhTest.setUp();
            dhTest.testDistantProgramDetection();
            dhTest.tearDown();
            dhTest.setUp();
            dhTest.testSourceRequiresDistantHorizons();
            dhTest.tearDown();
            dhTest.setUp();
            dhTest.testAvailabilityOverride();
            dhTest.tearDown();
            dhTest.setUp();
            dhTest.testAnalyzerSkipsDistantWithoutCompiling();
            dhTest.tearDown();
            dhTest.setUp();
            dhTest.testCatalogDistantQueries();
            dhTest.tearDown();
            dhTest.setUp();
            dhTest.testCapabilityGatePartialForDistant();
            dhTest.tearDown();
            dhTest.setUp();
            dhTest.testExecutionPlanSkipsDistant();
            dhTest.tearDown();
            dhTest.setUp();
            dhTest.testTranslationServiceCleanRejectsDistantProgram();
            dhTest.tearDown();
            dhTest.setUp();
            dhTest.testTranslationServiceUnaffectedForPlainProgram();
            dhTest.tearDown();
            System.out.println(">>> PASS: DistantHorizonsDegradeTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: DistantHorizonsDegradeTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // Temporal history invalidator (Phase 11)
        try {
            System.out.println("\n[Phase11] Running TemporalHistoryInvalidatorTest...");
            TemporalHistoryInvalidatorTest thTest = new TemporalHistoryInvalidatorTest();
            thTest.invalidatesAndClears();
            System.out.println(">>> PASS: TemporalHistoryInvalidatorTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: TemporalHistoryInvalidatorTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // MRT capability (Phase 7)
        try {
            System.out.println("\n[Phase7] Running MrtCapabilityTest...");
            MrtCapabilityTest mrtTest = new MrtCapabilityTest();
            mrtTest.nativeMrtSatisfiesWithinLimit();
            mrtTest.fallbackSatisfiesAny();
            System.out.println(">>> PASS: MrtCapabilityTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: MrtCapabilityTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // Render family registry (Phase 9)
        try {
            System.out.println("\n[Phase9] Running RenderFamilyTest...");
            RenderFamilyTest rfTest = new RenderFamilyTest();
            rfTest.registryHasFamilies();
            rfTest.baseRenderedFamiliesHaveFallbackReason();
            System.out.println(">>> PASS: RenderFamilyTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: RenderFamilyTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // First frame guard (Phase 6)
        try {
            System.out.println("\n[Phase6] Running FirstFrameGuardTest...");
            FirstFrameGuardTest ffTest = new FirstFrameGuardTest();
            ffTest.guardsFirstFrame();
            System.out.println(">>> PASS: FirstFrameGuardTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: FirstFrameGuardTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // G-buffer layout (Phase 7)
        try {
            System.out.println("\n[Phase7] Running GBufferLayoutTest...");
            GBufferLayoutTest gbTest = new GBufferLayoutTest();
            gbTest.hasAttachments();
            System.out.println(">>> PASS: GBufferLayoutTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: GBufferLayoutTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // Color space (Phase 10)
        try {
            System.out.println("\n[Phase10] Running ColorSpaceTest...");
            ColorSpaceTest csTest = new ColorSpaceTest();
            csTest.roundTrip();
            System.out.println(">>> PASS: ColorSpaceTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: ColorSpaceTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // Uniform catalogue (Phase 10)
        try {
            System.out.println("\n[Phase10] Running UniformCatalogueTest...");
            UniformCatalogueTest ucTest = new UniformCatalogueTest();
            ucTest.catalogueIsNonEmptyAndUnique();
            ucTest.supportedLookupWorks();
            System.out.println(">>> PASS: UniformCatalogueTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: UniformCatalogueTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        // Vitrail-guided visual pipeline (terrain hook + uniform transport + pack state)
        try {
            System.out.println("\n[Vitrail] Running VulkanModTerrainHookTest...");
            VulkanModTerrainHookTest vthTest = new VulkanModTerrainHookTest();
            vthTest.testProgramNameForSolid();
            vthTest.testBindDoesNotThrow();
            vthTest.testUnbindResetsBound();
            System.out.println(">>> PASS: VulkanModTerrainHookTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: VulkanModTerrainHookTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        try {
            System.out.println("\n[Vitrail] Running UniformTransportTest...");
            UniformTransportTest utTest = new UniformTransportTest();
            utTest.testUploadUniformsNullSafe();
            utTest.testWriteOfGlobalsStd140();
            System.out.println(">>> PASS: UniformTransportTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: UniformTransportTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        try {
            System.out.println("\n[Vitrail] Running PackExecutionStateTest...");
            PackExecutionStateTest pesTest = new PackExecutionStateTest();
            pesTest.testResetToNoPack();
            System.out.println(">>> PASS: PackExecutionStateTest");
            passed++;
        } catch (Throwable t) {
            System.err.println(">>> FAIL: PackExecutionStateTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        System.out.println("\n=================================================");
        System.out.println(String.format("   Test Suite Finished: %d Passed, %d Failed", passed, failed));
        System.out.println("=================================================");

        if (failed > 0) {
            System.exit(1);
        }
    }
}
