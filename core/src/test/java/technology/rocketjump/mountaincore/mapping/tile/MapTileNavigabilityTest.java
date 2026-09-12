package technology.rocketjump.mountaincore.mapping.tile;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.EntityType;
import technology.rocketjump.mountaincore.entities.model.physical.PhysicalEntityComponent;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemType;
import technology.rocketjump.mountaincore.entities.model.physical.plant.PlantEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.plant.PlantSpecies;
import technology.rocketjump.mountaincore.entities.model.physical.plant.PlantSpeciesType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * isNavigable walked the tile's entities twice, once through hasTree(). These cover the cases that
 * the hasTree() call used to decide.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class MapTileNavigabilityTest {

	private long nextEntityId = 1L;

	private MapTile emptyTile() {
		return new MapTile(1234L, 5, 7, null, null);
	}

	private Entity plant(PlantSpeciesType speciesType) {
		PlantSpecies species = mock(PlantSpecies.class);
		when(species.getPlantType()).thenReturn(speciesType);
		PlantEntityAttributes attributes = mock(PlantEntityAttributes.class);
		when(attributes.isTree()).thenReturn(speciesType.equals(PlantSpeciesType.TREE));
		when(attributes.getSpecies()).thenReturn(species);
		return entityOfType(EntityType.PLANT, attributes);
	}

	private Entity item(boolean blocksMovement) {
		ItemType itemType = mock(ItemType.class);
		when(itemType.blocksMovement()).thenReturn(blocksMovement);
		ItemEntityAttributes attributes = mock(ItemEntityAttributes.class);
		when(attributes.getItemType()).thenReturn(itemType);
		return entityOfType(EntityType.ITEM, attributes);
	}

	private Entity entityOfType(EntityType type, Object attributes) {
		PhysicalEntityComponent physicalComponent = mock(PhysicalEntityComponent.class);
		when(physicalComponent.getAttributes()).thenReturn(
				(technology.rocketjump.mountaincore.entities.model.physical.EntityAttributes) attributes);
		Entity entity = mock(Entity.class);
		when(entity.getType()).thenReturn(type);
		when(entity.getId()).thenReturn(nextEntityId++);
		when(entity.getPhysicalEntityComponent()).thenReturn(physicalComponent);
		return entity;
	}

	@Test
	public void anEmptyTileIsNavigable() {
		assertThat(emptyTile().isNavigable(null)).isTrue();
	}

	@Test
	public void aTileHoldingATreeIsNotNavigable() {
		MapTile tile = emptyTile();
		tile.addEntity(plant(PlantSpeciesType.TREE));

		assertThat(tile.isNavigable(null)).isFalse();
	}

	@Test
	public void aTileHoldingACropIsStillNavigable() {
		MapTile tile = emptyTile();
		tile.addEntity(plant(PlantSpeciesType.CROP));

		assertThat(tile.isNavigable(null)).isTrue();
	}

	@Test
	public void anItemWhichBlocksMovementIsStillHonouredAlongsideAPlant() {
		MapTile tile = emptyTile();
		tile.addEntity(plant(PlantSpeciesType.CROP));
		tile.addEntity(item(true));

		assertThat(tile.isNavigable(null)).isFalse();
	}

	@Test
	public void anOrdinaryItemDoesNotBlockTheTile() {
		MapTile tile = emptyTile();
		tile.addEntity(item(false));

		assertThat(tile.isNavigable(null)).isTrue();
	}
}
