package technology.rocketjump.mountaincore.entities.ai.goap.actions.location;

import com.badlogic.gdx.math.Vector2;
import technology.rocketjump.mountaincore.entities.ai.goap.AssignedGoal;
import technology.rocketjump.mountaincore.entities.model.physical.LocationComponent;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.mapping.model.TiledMap;
import technology.rocketjump.mountaincore.mapping.tile.MapTile;
import technology.rocketjump.mountaincore.settlement.ImmigrationManager;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

public class GoToSettlementAction extends GoToLocationAction {

	private static final int MAX_DISTANCE = 10;

	public GoToSettlementAction(AssignedGoal parent) {
		super(parent);
	}

	@Override
	protected Vector2 selectDestination(GameContext gameContext) {
		return findEmptyLocation(gameContext);
	}

	private Vector2 findEmptyLocation(GameContext gameContext) {
		TiledMap areaMap = gameContext.getAreaMap();
		LocationComponent locationComponent = parent.parentEntity.getLocationComponent();

		MapTile startingTile = areaMap.getTile(locationComponent.getWorldPosition());
		if (startingTile == null) {
			return null;
		}

		int targetRegionId = ImmigrationManager.determineSettlementRegionId(gameContext, false);

		Deque<MapTile> frontier = new ArrayDeque<>();
		Set<MapTile> explored = new HashSet<>();
		frontier.add(startingTile);
		explored.add(startingTile);

		while (!frontier.isEmpty()) {
			MapTile currentTile = frontier.pop();
			if (currentTile.isEmpty() && currentTile.getRegionId() == targetRegionId) {
				return currentTile.getWorldPositionOfCenter();
			}

			for (MapTile neighbour : areaMap.getNeighbours(currentTile.getTileX(), currentTile.getTileY()).values()) {
				// Mark tiles as they are queued rather than as they are taken off, or most are expanded several
				// times over.
				if (distance(startingTile, neighbour) < MAX_DISTANCE && explored.add(neighbour)) {
					frontier.add(neighbour);
				}
			}
		}

		return null;
	}

	private int distance(MapTile a, MapTile b) {
		return Math.abs(a.getTileX() - b.getTileX()) + Math.abs(a.getTileY() - b.getTileY());
	}

}
