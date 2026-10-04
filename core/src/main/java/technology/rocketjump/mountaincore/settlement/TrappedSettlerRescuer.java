package technology.rocketjump.mountaincore.settlement;

import com.badlogic.gdx.math.GridPoint2;
import com.badlogic.gdx.math.Vector2;
import org.pmw.tinylog.Logger;
import technology.rocketjump.mountaincore.entities.behaviour.creature.CreatureBehaviour;
import technology.rocketjump.mountaincore.entities.components.creature.SteeringComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.gamecontext.Updatable;
import technology.rocketjump.mountaincore.mapping.model.TiledMap;
import technology.rocketjump.mountaincore.mapping.tile.MapTile;
import technology.rocketjump.mountaincore.misc.VectorUtils;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

/**
 * The way out for a settler who has been shut in with no way back.
 * <p>
 * A builder who lays the last stone from the wrong side, a cave-in, a bridge taken up behind
 * someone: whatever the cause, a settler cut off from the rest of the settlement starves where
 * they stand and there is nothing the player can do but dig them out. Rather than leave them to
 * it, one who has been stuck long enough squeezes through to the other side.
 * <p>
 * It is meant to almost never happen. The settlement is walked over every few seconds, which costs
 * nothing, and the map's own regions - the patches of ground that connect to one another - say
 * whether somebody is cut off without any searching at all.
 */
@Singleton
public class TrappedSettlerRescuer implements Updatable {

	private static final float SECONDS_BETWEEN_CHECKS = 5f;
	/** Long enough that nothing momentary sets this off, short enough to beat starving */
	private static final double HOURS_STUCK_BEFORE_SQUEEZING_THROUGH = 3.0;
	/** How far to look for a way back before giving up on somebody this cycle */
	private static final int TILES_TO_SEARCH_FOR_A_WAY_BACK = 20;

	private final SettlerTracker settlerTracker;
	private final Map<Long, Double> cutOffSince = new HashMap<>();
	private GameContext gameContext;
	private float secondsSinceLastCheck;

	@Inject
	public TrappedSettlerRescuer(SettlerTracker settlerTracker) {
		this.settlerTracker = settlerTracker;
	}

	@Override
	public void update(float deltaTime) {
		if (gameContext == null || gameContext.getAreaMap() == null) {
			return;
		}
		secondsSinceLastCheck += deltaTime;
		if (secondsSinceLastCheck < SECONDS_BETWEEN_CHECKS) {
			return;
		}
		secondsSinceLastCheck = 0f;

		int settlementRegion = regionMostSettlersAreIn();
		if (settlementRegion == -1) {
			return;
		}
		double now = gameContext.getGameClock().getCurrentGameTime();

		for (Entity settler : settlerTracker.getLiving()) {
			MapTile standingOn = tileUnder(settler);
			if (standingOn == null || standingOn.getRegionId() == settlementRegion) {
				cutOffSince.remove(settler.getId());
				continue;
			}

			Double since = cutOffSince.putIfAbsent(settler.getId(), now);
			if (since != null && now - since > HOURS_STUCK_BEFORE_SQUEEZING_THROUGH) {
				squeezeThrough(settler, standingOn, settlementRegion);
			}
		}
		cutOffSince.keySet().removeIf(id -> gameContext.getEntities().get(id) == null);
	}

	/**
	 * Where the settlement is, as far as this is concerned: wherever most of its people are standing.
	 * Anyone somewhere else is on the wrong side of something.
	 */
	private int regionMostSettlersAreIn() {
		Map<Integer, Integer> headcount = new HashMap<>();
		for (Entity settler : settlerTracker.getLiving()) {
			MapTile standingOn = tileUnder(settler);
			if (standingOn != null) {
				headcount.merge(standingOn.getRegionId(), 1, Integer::sum);
			}
		}
		return headcount.entrySet().stream()
				.max(Map.Entry.comparingByValue())
				.map(Map.Entry::getKey)
				.orElse(-1);
	}

	private MapTile tileUnder(Entity settler) {
		if (settler.getLocationComponent() == null || settler.getLocationComponent().getContainerEntity() != null) {
			return null; // being carried, or riding something, so not standing anywhere of their own
		}
		Vector2 position = settler.getLocationComponent().getWorldOrParentPosition();
		return position == null ? null : gameContext.getAreaMap().getTile(position);
	}

	/**
	 * Puts the settler down on the nearest piece of ground that still belongs to the settlement,
	 * which is to say through whatever is in the way. Quietly: no message, no fuss, and the player
	 * is unlikely to be watching a corner nobody can reach.
	 */
	private void squeezeThrough(Entity settler, MapTile standingOn, int settlementRegion) {
		GridPoint2 wayBack = nearestTileInRegion(standingOn.getTilePosition(), settlementRegion, settler);
		if (wayBack == null) {
			return; // nowhere near enough to reach, try again on a later pass
		}

		settler.getLocationComponent().setWorldPosition(VectorUtils.toVector(wayBack), true);
		if (settler.getBehaviourComponent() != null) {
			SteeringComponent steering = settler.getBehaviourComponent().getSteeringComponent();
			if (steering != null) {
				// the route they were walking started on the other side of the wall
				steering.destinationReached();
			}
		}
		if (settler.getBehaviourComponent() instanceof CreatureBehaviour behaviour && behaviour.getCurrentGoal() != null) {
			behaviour.getCurrentGoal().setInterrupted(true); // whatever they were trying to do, they were not getting there
		}
		cutOffSince.remove(settler.getId());
		Logger.info("Freed a settler shut in at " + standingOn.getTilePosition() + ", put down at " + wayBack);
	}

	private GridPoint2 nearestTileInRegion(GridPoint2 from, int wantedRegion, Entity settler) {
		TiledMap map = gameContext.getAreaMap();
		for (int ring = 1; ring <= TILES_TO_SEARCH_FOR_A_WAY_BACK; ring++) {
			GridPoint2 found = null;
			int bestDistance = Integer.MAX_VALUE;
			for (int x = from.x - ring; x <= from.x + ring; x++) {
				for (int y = from.y - ring; y <= from.y + ring; y++) {
					// only the edge of the ring, the inside was covered by the smaller ones
					if (Math.abs(x - from.x) != ring && Math.abs(y - from.y) != ring) {
						continue;
					}
					MapTile candidate = map.getTile(x, y);
					if (candidate == null || candidate.getRegionId() != wantedRegion || !candidate.isNavigable(settler)) {
						continue;
					}
					int distance = (x - from.x) * (x - from.x) + (y - from.y) * (y - from.y);
					if (distance < bestDistance) {
						bestDistance = distance;
						found = candidate.getTilePosition();
					}
				}
			}
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	@Override
	public boolean runWhilePaused() {
		return false;
	}

	@Override
	public void onContextChange(GameContext gameContext) {
		this.gameContext = gameContext;
		cutOffSince.clear();
		secondsSinceLastCheck = 0f;
	}

	@Override
	public void clearContextRelatedState() {
		cutOffSince.clear();
	}
}
