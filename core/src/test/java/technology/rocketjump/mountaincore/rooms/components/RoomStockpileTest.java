package technology.rocketjump.mountaincore.rooms.components;

import com.badlogic.gdx.math.GridPoint2;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.EntityType;
import technology.rocketjump.mountaincore.entities.model.physical.PhysicalEntityComponent;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemType;
import technology.rocketjump.mountaincore.mapping.model.TiledMap;
import technology.rocketjump.mountaincore.mapping.tile.MapTile;
import technology.rocketjump.mountaincore.materials.model.GameMaterial;
import technology.rocketjump.mountaincore.production.StockpileAllocation;
import technology.rocketjump.mountaincore.rooms.Room;
import technology.rocketjump.mountaincore.rooms.RoomTile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A stockpile room reserves one tile per thing stored, and never used to give a reservation back.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class RoomStockpileTest {

	private static final GridPoint2 THE_ONE_TILE = new GridPoint2(5, 5);

	private RoomStockpile stockpile;
	private MapTile tile;
	private TiledMap map;
	private ItemType chest;
	private GameMaterial sycamore;

	@Before
	public void setUp() {
		Room room = mock(Room.class);
		when(room.getRoomTiles()).thenReturn(Map.of(THE_ONE_TILE, mock(RoomTile.class)));
		when(room.getRoomId()).thenReturn(1L);

		tile = mock(MapTile.class);
		when(tile.isEmpty()).thenReturn(true);
		when(tile.getEntities()).thenReturn(List.of());

		map = mock(TiledMap.class);
		when(map.getTile(THE_ONE_TILE)).thenReturn(tile);

		chest = mock(ItemType.class);
		sycamore = mock(GameMaterial.class);

		stockpile = new RoomStockpile(room);
	}

	private StockpileAllocation reservationFor(ItemType itemType, GameMaterial material, int incoming, int inTile) {
		StockpileAllocation allocation = new StockpileAllocation(THE_ONE_TILE);
		allocation.setItemType(itemType);
		allocation.setGameMaterial(material);
		allocation.incrementIncomingHaulingQuantity(incoming);
		allocation.setQuantityInTile(inTile);
		stockpile.getAllocations().put(THE_ONE_TILE, allocation);
		return allocation;
	}

	private void putOnTheTile(ItemType itemType, GameMaterial material, int quantity) {
		ItemEntityAttributes attributes = mock(ItemEntityAttributes.class);
		when(attributes.getItemType()).thenReturn(itemType);
		when(attributes.getPrimaryMaterial()).thenReturn(material);
		when(attributes.getQuantity()).thenReturn(quantity);

		PhysicalEntityComponent physical = mock(PhysicalEntityComponent.class);
		when(physical.getAttributes()).thenReturn(attributes);

		Entity item = mock(Entity.class);
		when(item.getType()).thenReturn(EntityType.ITEM);
		when(item.getPhysicalEntityComponent()).thenReturn(physical);

		when(tile.getEntities()).thenReturn(List.of(item));
		when(tile.isEmpty()).thenReturn(false);
	}

	@Test
	public void aTileReservedForSomethingLongGoneIsOfferedAgain() {
		// Saved as holding a chest, which has since been built into a piece of furniture
		reservationFor(chest, sycamore, 0, 1);

		ItemType carrotCrate = mock(ItemType.class);
		GameMaterial carrot = mock(GameMaterial.class);

		StockpileAllocation allocation = stockpile.createAllocation(map, carrotCrate, carrot, null);

		assertThat(allocation).isNotNull();
		assertThat(allocation.getItemType()).isEqualTo(carrotCrate);
		assertThat(stockpile.getAllocations().get(THE_ONE_TILE)).isSameAs(allocation);
	}

	@Test
	public void aTileWithADeliveryOnItsWayIsLeftAlone() {
		reservationFor(chest, sycamore, 4, 0);

		assertThat(stockpile.createAllocation(map, mock(ItemType.class), mock(GameMaterial.class), null)).isNull();
	}

	@Test
	public void aTileActuallyHoldingWhatItIsReservedForIsLeftAlone() {
		reservationFor(chest, sycamore, 0, 1);
		putOnTheTile(chest, sycamore, 1);

		assertThat(stockpile.createAllocation(map, mock(ItemType.class), mock(GameMaterial.class), null)).isNull();
		assertThat(stockpile.getAllocations()).containsKey(THE_ONE_TILE);
	}

	@Test
	public void anUnreservedEmptyTileIsHandedOutAsBefore() {
		assertThat(stockpile.createAllocation(map, chest, sycamore, null)).isNotNull();
	}
}
