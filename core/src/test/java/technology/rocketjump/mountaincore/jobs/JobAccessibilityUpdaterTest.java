package technology.rocketjump.mountaincore.jobs;

import com.badlogic.gdx.ai.msg.MessageDispatcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import technology.rocketjump.mountaincore.entities.EntityStore;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.jobs.model.Job;
import technology.rocketjump.mountaincore.jobs.model.JobCollection;
import technology.rocketjump.mountaincore.jobs.model.JobState;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * A job that has become reachable must not wait behind a backlog of jobs that never will be.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class JobAccessibilityUpdaterTest {

	private static final float FRAME_DELTA = 1f / 60f;

	@Mock
	private JobStore mockJobStore;
	@Mock
	private EntityStore mockEntityStore;
	@Mock
	private MessageDispatcher mockMessageDispatcher;
	@Mock
	private GameContext mockGameContext;
	@Mock
	private JobCollection mockInaccessibleJobs;
	@Mock
	private JobCollection mockPotentiallyAccessibleJobs;

	private JobAccessibilityUpdater jobAccessibilityUpdater;
	private final List<Job> retriedJobs = new ArrayList<>();

	@Before
	public void setUp() {
		when(mockJobStore.getCollectionByState(JobState.INACCESSIBLE)).thenReturn(mockInaccessibleJobs);
		when(mockJobStore.getCollectionByState(JobState.POTENTIALLY_ACCESSIBLE)).thenReturn(mockPotentiallyAccessibleJobs);
		// Nothing waiting to be re-checked, so only the retry half of update() does any work
		when(mockPotentiallyAccessibleJobs.next()).thenReturn(null);

		doAnswer(invocation -> {
			retriedJobs.add(invocation.getArgument(0));
			return null;
		}).when(mockJobStore).switchState(any(Job.class), eq(JobState.POTENTIALLY_ACCESSIBLE));

		jobAccessibilityUpdater = new JobAccessibilityUpdater(mockJobStore, mockEntityStore, mockMessageDispatcher);
		jobAccessibilityUpdater.onContextChange(mockGameContext);
	}

	private void givenInaccessibleBacklogOf(int backlogSize) {
		when(mockInaccessibleJobs.size()).thenReturn(backlogSize);
		when(mockInaccessibleJobs.next()).thenAnswer(invocation -> {
			Job job = new Job();
			job.setJobState(JobState.INACCESSIBLE);
			return job;
		});
	}

	private void runForSeconds(float seconds) {
		int frames = Math.round(seconds / FRAME_DELTA);
		for (int frame = 0; frame < frames; frame++) {
			jobAccessibilityUpdater.update(FRAME_DELTA);
		}
	}

	@Test
	public void everyInaccessibleJobIsRetriedWithinTheSweepWindow() {
		// The backlog size taken from a real settlement which had stopped cooking entirely
		int backlogSize = 1500;
		givenInaccessibleBacklogOf(backlogSize);

		runForSeconds(JobAccessibilityUpdater.SECONDS_PER_FULL_INACCESSIBLE_SWEEP);

		assertThat(retriedJobs.size()).isGreaterThanOrEqualTo(backlogSize);
	}

	@Test
	public void aSmallBacklogIsStillRetriedAtTheOriginalFixedRate() {
		givenInaccessibleBacklogOf(1);

		runForSeconds(10f);

		// 10 seconds at one retry per 3.143 seconds
		assertThat(retriedJobs.size()).isGreaterThanOrEqualTo(3);
	}

	@Test
	public void jobsWhichHaveAlreadyChangedStateAreNotRetried() {
		// next() walks a snapshot which can still hold jobs that have since moved to another state
		when(mockInaccessibleJobs.size()).thenReturn(500);
		when(mockInaccessibleJobs.next()).thenAnswer(invocation -> {
			Job staleJob = new Job();
			staleJob.setJobState(JobState.ASSIGNED);
			return staleJob;
		});

		runForSeconds(JobAccessibilityUpdater.SECONDS_PER_FULL_INACCESSIBLE_SWEEP);

		assertThat(retriedJobs).isEmpty();
	}
}
