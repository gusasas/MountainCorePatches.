package technology.rocketjump.mountaincore.jobs;

import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.badlogic.gdx.math.GridPoint2;
import com.badlogic.gdx.math.Vector2;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.pmw.tinylog.Logger;
import technology.rocketjump.mountaincore.entities.EntityStore;
import technology.rocketjump.mountaincore.entities.components.creature.SkillsComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.gamecontext.Updatable;
import technology.rocketjump.mountaincore.jobs.model.Job;
import technology.rocketjump.mountaincore.jobs.model.JobState;
import technology.rocketjump.mountaincore.mapping.tile.CompassDirection;
import technology.rocketjump.mountaincore.mapping.tile.MapTile;
import technology.rocketjump.mountaincore.mapping.tile.TileNeighbours;
import technology.rocketjump.mountaincore.messaging.MessageType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static technology.rocketjump.mountaincore.entities.ai.goap.actions.location.GoToLocationAction.calculatePosition;
import static technology.rocketjump.mountaincore.misc.VectorUtils.toGridPoint;

/**
 * This class is mostly responsible for switching potentially accessible jobs to assignable jobs
 */
@Singleton
public class JobAccessibilityUpdater implements Updatable {

	public static final float TIME_BETWEEN_INACCESSIBLE_RETRIES = 3.143f;
	/**
	 * Retry the whole inaccessible backlog within this many seconds, so a large one cannot starve the
	 * queue of jobs that have become reachable.
	 */
	public static final float SECONDS_PER_FULL_INACCESSIBLE_SWEEP = 30f;
	/**
	 * Upper bound per update so a pathological backlog can't cause a frame spike
	 */
	private static final int MAX_RETRIES_PER_UPDATE = 25;

	private final JobStore jobStore;
	private final EntityStore entityStore;
	private final MessageDispatcher messageDispatcher;

	private GameContext gameContext;
	private float retriesDue = 0f;

	@Inject
	public JobAccessibilityUpdater(JobStore jobStore, EntityStore entityStore, MessageDispatcher messageDispatcher) {
		this.jobStore = jobStore;
		this.entityStore = entityStore;
		this.messageDispatcher = messageDispatcher;
	}

	/**
	 * Retries inaccessible jobs at a rate which scales with how many there are, then re-checks
	 * the same number of potentially accessible jobs so the two queues stay in step.
	 * @param deltaTime
	 */
	@Override
	public void update(float deltaTime) {
		if (gameContext == null) {
			return;
		}

		int inaccessibleJobCount = jobStore.getCollectionByState(JobState.INACCESSIBLE).size();
		float retriesPerSecond = Math.max(1f / TIME_BETWEEN_INACCESSIBLE_RETRIES,
				inaccessibleJobCount / SECONDS_PER_FULL_INACCESSIBLE_SWEEP);
		retriesDue = Math.min(retriesDue + (retriesPerSecond * deltaTime), MAX_RETRIES_PER_UPDATE);

		int retriesThisUpdate = (int) retriesDue;
		retriesDue -= retriesThisUpdate;

		for (int cursor = 0; cursor < retriesThisUpdate; cursor++) {
			Job inaccessibleJob = jobStore.getCollectionByState(JobState.INACCESSIBLE).next();
			if (inaccessibleJob == null) {
				break;
			}
			// next() walks a snapshot which can still hold jobs that have already changed state,
			// so only promote the ones which really are still inaccessible
			if (inaccessibleJob.getJobState().equals(JobState.INACCESSIBLE)) {
				jobStore.switchState(inaccessibleJob, JobState.POTENTIALLY_ACCESSIBLE);
			}
		}

		for (int cursor = 0; cursor < Math.max(1, retriesThisUpdate); cursor++) {
			if (!checkNextPotentiallyAccessible()) {
				break;
			}
		}
	}

	/**
	 * @return false when there is nothing more worth checking this update
	 */
	private boolean checkNextPotentiallyAccessible() {
		Job potentiallyAccessibleJob = jobStore.getCollectionByState(JobState.POTENTIALLY_ACCESSIBLE).next();
		if (potentiallyAccessibleJob == null) {
			// No outstanding potentially accessible jobs
			return false;
		}
		if (!potentiallyAccessibleJob.getJobState().equals(JobState.POTENTIALLY_ACCESSIBLE)) {
			// Stale entry from the iteration snapshot, skip it but keep working through the queue
			return true;
		}
		Entity assignableEntity = getEntityToPathfindFrom(potentiallyAccessibleJob);
		if (assignableEntity == null) {
			// No entities to assign to
			return false;
		}
		Vector2 entityWorldPosition = assignableEntity.getLocationComponent().getWorldOrParentPosition();

		List<GridPoint2> jobLocations = new ArrayList<>();

		if (potentiallyAccessibleJob.getHaulingAllocation() != null) {
			GridPoint2 haulingPosition = toGridPoint(calculatePosition(potentiallyAccessibleJob.getHaulingAllocation(), gameContext));
			if (haulingPosition == null) {
				// No navigable way to the allocation right now, e.g. furniture with no reachable workspace
				jobStore.switchState(potentiallyAccessibleJob, JobState.INACCESSIBLE);
				return true;
			}
			jobLocations.add(haulingPosition);
		} else if (potentiallyAccessibleJob.getType().isAccessedFromAdjacentTile()) {
			TileNeighbours jobNeighbourTiles = gameContext.getAreaMap().getOrthogonalNeighbours(potentiallyAccessibleJob.getJobLocation().x, potentiallyAccessibleJob.getJobLocation().y);
			for (CompassDirection compassDirection : jobNeighbourTiles.keySet()) {
				if (!jobNeighbourTiles.get(compassDirection).isNavigable(null)) {
					jobNeighbourTiles.remove(compassDirection);
				}
			}
			if (jobNeighbourTiles.isEmpty()) {
				// None of the adjacent tiles were accessible, so this job is actually inaccessible now
				jobStore.switchState(potentiallyAccessibleJob, JobState.INACCESSIBLE);
				return true;
			} else {
				for (MapTile mapTile : jobNeighbourTiles.values()) {
					jobLocations.add(mapTile.getTilePosition());
				}
				Collections.shuffle(jobLocations);
			}
		} else {
			if (potentiallyAccessibleJob.getJobLocation() == null) {
				Logger.error("Job location is null for job {}, will cancel", potentiallyAccessibleJob);
				messageDispatcher.dispatchMessage(MessageType.JOB_REMOVED, potentiallyAccessibleJob);
				return true;
			}
			jobLocations.add(potentiallyAccessibleJob.getJobLocation());
		}

		if (isLocationNavigable(jobLocations, entityWorldPosition)) {
			jobStore.switchState(potentiallyAccessibleJob, JobState.ASSIGNABLE);
		} else {
			jobStore.switchState(potentiallyAccessibleJob, JobState.INACCESSIBLE);
		}
		return true;
	}

	@Override
	public boolean runWhilePaused() {
		return true;
	}

	private Entity getEntityToPathfindFrom(Job job) {
		List<Entity> candidates = new ArrayList<>();
		for (Entity jobAssignableEntity : entityStore.getJobAssignableEntities()) {
			SkillsComponent skillsComponent = jobAssignableEntity.getComponent(SkillsComponent.class);
			if (skillsComponent != null && skillsComponent.hasActiveProfession(job.getRequiredProfession())) {
				candidates.add(jobAssignableEntity);
			}
		}

		if (candidates.isEmpty()) {
			return null;
		} else {
			return candidates.get(gameContext.getRandom().nextInt(candidates.size()));
		}
	}

	private boolean isLocationNavigable(List<GridPoint2> locations, Vector2 entityWorldPosition) {
		MapTile originTile = gameContext.getAreaMap().getTile(entityWorldPosition);
		if (originTile == null) {
			return false;
		}

		// The job is accessible if ANY of the candidate locations is reachable. Testing only one at
		// random condemned jobs which were perfectly reachable from another of their workspaces.
		for (GridPoint2 locationToTry : locations) {
			MapTile targetTile = gameContext.getAreaMap().getTile(locationToTry);
			// Just checking if job is in same region
			if (targetTile != null && originTile.getRegionId() == targetTile.getRegionId()) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void onContextChange(GameContext gameContext) {
		this.gameContext = gameContext;
	}

	@Override
	public void clearContextRelatedState() {
	}

}
