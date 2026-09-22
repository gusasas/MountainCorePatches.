package technology.rocketjump.mountaincore.production;

import com.badlogic.gdx.math.GridPoint2;
import com.badlogic.gdx.math.Vector2;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import technology.rocketjump.mountaincore.entities.components.InventoryComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.physical.LocationComponent;
import technology.rocketjump.mountaincore.entities.model.physical.PhysicalEntityComponent;
import technology.rocketjump.mountaincore.entities.model.physical.creature.Race;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemType;
import technology.rocketjump.mountaincore.materials.model.GameMaterial;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A corpse booked into a chest has a race and no item type, which used to take the game down.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FurnitureStockpileTest {

	private FurnitureStockpile stockpile;
	private Entity chest;
	private InventoryComponent chestInventory;

	@Before
	public void setUp() {
		chestInventory = mock(InventoryComponent.class);
		LocationComponent chestLocation = mock(LocationComponent.class);
		when(chestLocation.getWorldPosition()).thenReturn(new Vector2(4f, 7f));

		chest = mock(Entity.class);
		when(chest.getComponent(InventoryComponent.class)).thenReturn(chestInventory);
		when(chest.getLocationComponent()).thenReturn(chestLocation);

		stockpile = new FurnitureStockpile();
		stockpile.setParentEntity(chest);
		stockpile.setMaxQuantity(6);
	}

	private Entity itemOf(ItemType itemType, GameMaterial material, int quantity) {
		ItemEntityAttributes attributes = mock(ItemEntityAttributes.class);
		when(attributes.getItemType()).thenReturn(itemType);
		when(attributes.getPrimaryMaterial()).thenReturn(material);
		when(attributes.getQuantity()).thenReturn(quantity);

		PhysicalEntityComponent physical = mock(PhysicalEntityComponent.class);
		when(physical.getAttributes()).thenReturn(attributes);

		Entity item = mock(Entity.class);
		when(item.getPhysicalEntityComponent()).thenReturn(physical);
		return item;
	}

	@SuppressWarnings("unchecked")
	private void givenCorpseIsOnItsWay() throws Exception {
		StockpileAllocation corpseAllocation = new StockpileAllocation(new GridPoint2(4, 7));
		corpseAllocation.setRaceCorpse(mock(Race.class));
		// Only reachable through hauling in the running game.
		Field field = FurnitureStockpile.class.getDeclaredField("allocationsByHaulingAllocationId");
		field.setAccessible(true);
		((Map<Long, StockpileAllocation>) field.get(stockpile)).put(1L, corpseAllocation);
	}

	@Test
	public void aCorpseOnItsWayDoesNotStopAnItemBeingAddedToTheStack() throws Exception {
		ItemType carrotCrate = mock(ItemType.class);
		GameMaterial carrot = mock(GameMaterial.class);
		Entity alreadyInChest = itemOf(carrotCrate, carrot, 4);
		InventoryComponent.InventoryEntry entry = mock(InventoryComponent.InventoryEntry.class);
		entry.entity = alreadyInChest;
		when(chestInventory.findByItemTypeAndMaterial(carrotCrate, carrot, null)).thenReturn(entry);

		givenCorpseIsOnItsWay();

		StockpileAllocation allocation = stockpile.findExistingAllocation(itemOf(carrotCrate, carrot, 1), null, 30, 1);

		assertThat(allocation).isNotNull();
		assertThat(allocation.getItemType()).isEqualTo(carrotCrate);
	}

	@Test
	public void deliveriesMergingIntoOneStackDoNotUseUpEveryPlace() throws Exception {
		ItemType planks = mock(ItemType.class);
		when(planks.getItemTypeName()).thenReturn("Resource-Planks");
		GameMaterial sycamore = mock(GameMaterial.class);

		// One stack of planks sat in the chest, and a pile of small deliveries booked against it
		Entity alreadyInChest = itemOf(planks, sycamore, 7);
		InventoryComponent.InventoryEntry entry = mock(InventoryComponent.InventoryEntry.class);
		entry.entity = alreadyInChest;
		when(chestInventory.findByItemTypeAndMaterial(planks, sycamore, null)).thenReturn(entry);
		when(chestInventory.getInventoryEntries()).thenReturn(List.of(entry));

		Field field = FurnitureStockpile.class.getDeclaredField("allocationsByHaulingAllocationId");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<Long, StockpileAllocation> allocations = (Map<Long, StockpileAllocation>) field.get(stockpile);
		for (long i = 0; i < 15; i++) {
			StockpileAllocation incoming = new StockpileAllocation(new GridPoint2(4, 7));
			incoming.setItemType(planks);
			incoming.setGameMaterial(sycamore);
			incoming.incrementIncomingHaulingQuantity(2);
			allocations.put(i, incoming);
		}

		// Something of another kind should still find room: those fifteen trips share one place
		ItemType carrotCrate = mock(ItemType.class);
		when(carrotCrate.getItemTypeName()).thenReturn("Ingredient-Vegetable-Crate");
		GameMaterial carrot = mock(GameMaterial.class);

		assertThat(stockpile.createAllocation(null, carrotCrate, carrot, null)).isNotNull();
	}

	@Test
	public void aFullChestTurnsAwaySomethingThatWouldNeedAPlaceOfItsOwn() {
		ItemType seeds = mock(ItemType.class);
		GameMaterial carrotSeed = mock(GameMaterial.class);

		// Six stacks in a chest with six places, so every place is taken
		List<InventoryComponent.InventoryEntry> full = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			InventoryComponent.InventoryEntry entry = mock(InventoryComponent.InventoryEntry.class);
			entry.entity = itemOf(seeds, carrotSeed, 50);
			full.add(entry);
		}
		when(chestInventory.getInventoryEntries()).thenReturn(full);
		when(chestInventory.wouldMergeIntoExisting(any())).thenReturn(false);

		// A different seed needs a stack of its own, and there is nowhere to put it
		assertThat(stockpile.canAccept(itemOf(seeds, mock(GameMaterial.class), 10))).isFalse();
	}

	@Test
	public void aFullChestStillTakesSomethingThatJoinsAStackAlreadyInIt() {
		ItemType seeds = mock(ItemType.class);
		GameMaterial carrotSeed = mock(GameMaterial.class);

		List<InventoryComponent.InventoryEntry> full = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			InventoryComponent.InventoryEntry entry = mock(InventoryComponent.InventoryEntry.class);
			entry.entity = itemOf(seeds, carrotSeed, 20);
			full.add(entry);
		}
		when(chestInventory.getInventoryEntries()).thenReturn(full);
		when(chestInventory.wouldMergeIntoExisting(any())).thenReturn(true);

		assertThat(stockpile.canAccept(itemOf(seeds, carrotSeed, 5))).isTrue();
	}

	@Test
	public void aChestWithAPlaceLeftTakesSomethingNew() {
		InventoryComponent.InventoryEntry entry = mock(InventoryComponent.InventoryEntry.class);
		entry.entity = itemOf(mock(ItemType.class), mock(GameMaterial.class), 1);
		when(chestInventory.getInventoryEntries()).thenReturn(List.of(entry));
		when(chestInventory.wouldMergeIntoExisting(any())).thenReturn(false);

		assertThat(stockpile.canAccept(itemOf(mock(ItemType.class), mock(GameMaterial.class), 1))).isTrue();
	}

	@Test
	public void furnitureWithNoInventoryIsSimplyNotUsed() {
		when(chest.getComponent(InventoryComponent.class)).thenReturn(null);

		assertThat(stockpile.findExistingAllocation(itemOf(mock(ItemType.class), mock(GameMaterial.class), 1), null, 30, 1)).isNull();
	}
}
