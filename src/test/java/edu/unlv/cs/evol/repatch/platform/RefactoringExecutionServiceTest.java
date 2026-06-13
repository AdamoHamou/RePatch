package edu.unlv.cs.evol.repatch.platform;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import edu.unlv.cs.evol.repatch.utils.FailureEventSink;

import java.util.List;

/**
 * Contract tests for the Week 4A six-step execution protocol: each failure
 * mode classifies into the right {@link RefactoringExecutionResult.Status},
 * post-operation synchronization runs exactly when execution succeeds, and
 * postconditions are checked after sync.
 */
public class RefactoringExecutionServiceTest extends LightJavaCodeInsightFixtureTestCase {

    private InMemoryPlatformFacade facade;
    private RefactoringExecutionService service;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        facade = new InMemoryPlatformFacade();
        service = new RefactoringExecutionService(
                new RefactoringExecutionContext(getProject(), facade));
        FailureEventSink.clear();
    }

    private static RefactoringOperation operation(String description, ThrowingPrepare prepare,
                                                  Runnable execute, String postconditionFailure) {
        return new RefactoringOperation() {
            @Override
            public String describe() {
                return description;
            }

            @Override
            public void prepare(RefactoringExecutionContext context) throws PreconditionFailed, UnsupportedShape {
                prepare.run();
            }

            @Override
            public void execute(RefactoringExecutionContext context) {
                execute.run();
            }

            @Override
            public String verifyPostcondition(RefactoringExecutionContext context) {
                return postconditionFailure;
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingPrepare {
        void run() throws RefactoringOperation.PreconditionFailed, RefactoringOperation.UnsupportedShape;
    }

    public void testSuccessRunsAllStepsAndSynchronizes() {
        boolean[] executed = {false};
        RefactoringExecutionResult result = service.execute(
                operation("op", () -> { }, () -> executed[0] = true, null));

        assertTrue(result.isSuccess());
        assertEquals(RefactoringExecutionResult.Status.SUCCESS, result.getStatus());
        assertTrue("execute should have run", executed[0]);
        assertEquals("post-operation sync must run once on success", 1, facade.commitAndReparseCount);
    }

    public void testPreconditionFailureClassifiesAndSkipsExecution() {
        boolean[] executed = {false};
        RefactoringExecutionResult result = service.execute(operation("op",
                () -> { throw new RefactoringOperation.PreconditionFailed("target missing"); },
                () -> executed[0] = true, null));

        assertEquals(RefactoringExecutionResult.Status.PRECONDITION_FAILED, result.getStatus());
        assertEquals("target missing", result.getDetail());
        assertFalse("execute must not run after a failed prepare", executed[0]);
        assertEquals("no post-operation sync without execution", 0, facade.commitAndReparseCount);
    }

    public void testUnsupportedShapeClassifies() {
        RefactoringExecutionResult result = service.execute(operation("op",
                () -> { throw new RefactoringOperation.UnsupportedShape("odd form"); },
                () -> { }, null));

        assertEquals(RefactoringExecutionResult.Status.UNSUPPORTED_SHAPE, result.getStatus());
        assertEquals("odd form", result.getDetail());
    }

    public void testProcessorThrowClassifiesAndCapturesFailure() {
        RuntimeException boom = new RuntimeException("processor blew up");
        RefactoringExecutionResult result = service.execute(
                operation("op", () -> { }, () -> { throw boom; }, null));

        assertEquals(RefactoringExecutionResult.Status.PROCESSOR_THREW, result.getStatus());
        assertSame(boom, result.getFailure());
        assertEquals("no post-operation sync after a processor failure", 0, facade.commitAndReparseCount);
    }

    public void testPostconditionBrokenClassifiesAfterSync() {
        RefactoringExecutionResult result = service.execute(
                operation("op", () -> { }, () -> { }, "state missing"));

        assertEquals(RefactoringExecutionResult.Status.POSTCONDITION_BROKEN, result.getStatus());
        assertEquals("state missing", result.getDetail());
        assertEquals("sync still runs before the postcondition check", 1, facade.commitAndReparseCount);
    }

    public void testEveryFailureIsRecordedToTheSinkAndSuccessIsNot() {
        service.execute(operation("invert RENAME_METHOD (m)",
                () -> { throw new RefactoringOperation.PreconditionFailed("target missing"); },
                () -> { }, null));
        service.execute(operation("replay MOVE_OPERATION (m)", () -> { }, () -> { }, null));

        List<FailureEventSink.Entry> events = FailureEventSink.drain();
        assertEquals("only the failure must be recorded", 1, events.size());
        assertEquals("INVERT", events.get(0).phase);
        assertEquals("RENAME_METHOD", events.get(0).refactoringType);
        assertEquals("PRECONDITION_FAILED", events.get(0).category);
        assertTrue("evidence carries the classified log line",
                events.get(0).evidence.contains("[RefactoringExecution] PRECONDITION_FAILED"));
    }
}
