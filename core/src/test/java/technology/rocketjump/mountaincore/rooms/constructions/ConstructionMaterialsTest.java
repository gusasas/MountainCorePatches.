package technology.rocketjump.mountaincore.rooms.constructions;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.physical.PhysicalEntityComponent;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemType;
import technology.rocketjump.mountaincore.entities.model.physical.item.QuantifiedItemTypeWithMaterial;
import technology.rocketjump.mountaincore.materials.model.GameMaterial;
import technology.rocketjump.mountaincore.materials.model.GameMaterialType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a construction counts as a building material is destroyed when it is finished.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ConstructionMaterialsTest {

	private FurnitureConstruction construction;
	private ItemType chest;
	private ItemType planks;
	private GameMaterial sycamore;
	private GameMaterial beech;

	@Before
	public void setUp() {
		construction = new FurnitureConstruction();

		chest = mock(ItemType.class);
		planks = mock(ItemType.class);
		sycamore = mock(GameMaterial.class);
		beech = mock(GameMaterial.class);
		when(sycamore.getMaterialType()).thenReturn(GameMaterialType.WOOD);
		when(beech.getMaterialType()).thenReturn(GameMaterialType.WOOD);
	}

	private void requires(ItemType itemType, GameMaterial material) {
		QuantifiedItemTypeWithMaterial requirement = new QuantifiedItemTypeWithMaterial();
		requirement.setItemType(itemType);
		requirement.setMaterial(material);
		requirement.setQuantity(1);
		construction.getRequirements().add(requirement);
	}

	private Entity itemOf(ItemType itemType, GameMaterial material) {
		ItemEntityAttributes attributes = mock(ItemEntityAttributes.class);
		when(attributes.getItemType()).thenReturn(itemType);
		when(attributes.getMaterial(GameMaterialType.WOOD)).thenReturn(material);

		PhysicalEntityComponent physical = mock(PhysicalEntityComponent.class);
		when(physical.getAttributes()).thenReturn(attributes);

		Entity item = mock(Entity.class);
		when(item.getPhysicalEntityComponent()).thenReturn(physical);
		return item;
	}

	/**
	 * The public constructor wants a piece of furniture placed on the map; only the piece matters here.
	 */
	private FurnitureConstruction constructionFor(Entity ghost) {
		try {
			FurnitureConstruction built = new FurnitureConstruction();
			java.lang.reflect.Field field = FurnitureConstruction.class.getDeclaredField("furnitureEntityToBePlaced");
			field.setAccessible(true);
			field.set(built, ghost);
			return built;
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private Entity ghostChestHolding(java.util.List<technology.rocketjump.mountaincore.entities.components.InventoryComponent.InventoryEntry> contents,
	                                 boolean settingsAllowIt) {
		technology.rocketjump.mountaincore.entities.components.InventoryComponent inventory =
				mock(technology.rocketjump.mountaincore.entities.components.InventoryComponent.class);
		when(inventory.getInventoryEntries()).thenReturn(contents);

		Entity ghost = mock(Entity.class);
		when(ghost.getComponent(technology.rocketjump.mountaincore.entities.components.InventoryComponent.class)).thenReturn(inventory);

		technology.rocketjump.mountaincore.production.FurnitureStockpile stockpile =
				new technology.rocketjump.mountaincore.production.FurnitureStockpile();
		stockpile.setParentEntity(ghost);
		stockpile.setMaxQuantity(6);

		technology.rocketjump.mountaincore.production.StockpileSettings settings =
				mock(technology.rocketjump.mountaincore.production.StockpileSettings.class);
		when(settings.canHold(org.mockito.ArgumentMatchers.any())).thenReturn(settingsAllowIt);

		technology.rocketjump.mountaincore.entities.components.furniture.FurnitureStockpileComponent component =
				mock(technology.rocketjump.mountaincore.entities.components.furniture.FurnitureStockpileComponent.class);
		when(component.getStockpile()).thenReturn(stockpile);
		when(component.getStockpileSettings()).thenReturn(settings);
		when(ghost.getComponent(technology.rocketjump.mountaincore.entities.components.furniture.FurnitureStockpileComponent.class))
				.thenReturn(component);
		return ghost;
	}

	@Test
	public void aChestGoingUpOverAStackItWillHoldLeavesItWhereItIs() {
		construction = constructionFor(ghostChestHolding(java.util.List.of(), true));
		requires(chest, sycamore);

		assertThat(construction.willTakeInOnCompletion(itemOf(planks, beech))).isTrue();
	}

	@Test
	public void aChestDoesNotAdoptItsOwnBuildingMaterials() {
		construction = constructionFor(ghostChestHolding(java.util.List.of(), true));
		requires(chest, sycamore);

		// The chest it is built out of is consumed, not stored
		assertThat(construction.willTakeInOnCompletion(itemOf(chest, sycamore))).isFalse();
	}

	@Test
	public void aChestTurnsDownWhatItsSettingsRefuse() {
		construction = constructionFor(ghostChestHolding(java.util.List.of(), false));
		requires(chest, sycamore);

		assertThat(construction.willTakeInOnCompletion(itemOf(planks, beech))).isFalse();
	}

	@Test
	public void somethingWithNowhereToPutThingsKeepsClearingTheSiteAsBefore() {
		construction = constructionFor(mock(Entity.class));
		requires(chest, sycamore);

		assertThat(construction.willTakeInOnCompletion(itemOf(planks, beech))).isFalse();
	}

	@Test
	public void theThingItAskedForCountsAsAMaterial() {
		requires(chest, sycamore);

		assertThat(construction.isItemUsedInConstruction(itemOf(chest, sycamore))).isTrue();
	}

	@Test
	public void somethingElseMadeOfTheSameStuffIsNotAMaterial() {
		requires(chest, sycamore);

		// Sycamore planks are not a sycamore chest, and used to be swallowed by the construction
		assertThat(construction.isItemUsedInConstruction(itemOf(planks, sycamore))).isFalse();
	}

	@Test
	public void theRightThingInTheWrongStuffIsNotAMaterial() {
		requires(chest, sycamore);

		assertThat(construction.isItemUsedInConstruction(itemOf(chest, beech))).isFalse();
	}

	@Test
	public void withNoMaterialAskedForTheItemTypeAloneDecides() {
		requires(chest, null);

		assertThat(construction.isItemUsedInConstruction(itemOf(chest, beech))).isTrue();
		assertThat(construction.isItemUsedInConstruction(itemOf(planks, beech))).isFalse();
	}
}
