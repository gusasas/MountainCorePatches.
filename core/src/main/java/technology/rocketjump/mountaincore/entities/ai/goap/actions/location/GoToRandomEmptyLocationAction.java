package technology.rocketjump.mountaincore.entities.ai.goap.actions.location;

import com.badlogic.gdx.math.Vector2;
import technology.rocketjump.mountaincore.entities.ai.goap.AssignedGoal;
import technology.rocketjump.mountaincore.entities.ai.goap.actions.IdleAction;
import technology.rocketjump.mountaincore.entities.model.physical.LocationComponent;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.mapping.model.TiledMap;
import technology.rocketjump.mountaincore.mapping.tile.MapTile;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

public class GoToRandomEmptyLocationAction extends GoToLocationAction {

	private static final int MAX_DISTANCE = 10;
	private static final int MIN_DISTANCE = 3; //for aesthetics

	public GoToRandomEmptyLocationAction(AssignedGoal parent) {
		super(parent);
	}

	@Override
	protected Vector2 selectDestination(GameContext gameContext) {
		Vector2 location = findEmptyLocation(gameContext);
		if (location == null) {
			return IdleAction.pickRandomLocation(gameContext, parent.parentEntity);
		} else {
			return location;
		}
	}

	private Vector2 findEmptyLocation(GameContext gameContext) {
		TiledMap areaMap = gameContext.getAreaMap();
		LocationComponent locationComponent = parent.parentEntity.getLocationComponent();
		
		MapTile startingTile = areaMap.getTile(locationComponent.getWorldPosition());

		Deque<MapTile> frontier = new ArrayDeque<>();
		Set<MapTile> explored = new HashSet<>();
		frontier.add(startingTile);
		explored.add(startingTile);

		while (!frontier.isEmpty()) {
			MapTile currentTile = frontier.pop();
			if (currentTile.isEmpty() &&
				distance(startingTile, currentTile) >= MIN_DISTANCE) {
				return currentTile.getWorldPositionOfCenter();
			}

			for (MapTile neighbour : areaMap.getNeighbours(currentTile.getTileX(), currentTile.getTileY()).values()) {
				// Mark tiles as they are queued rather than as they are taken off, or most are expanded several
				// times over.
				if (neighbour.getRegionId() == currentTile.getRegionId() &&
						distance(startingTile, neighbour) < MAX_DISTANCE &&
						explored.add(neighbour)) {
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
