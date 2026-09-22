package technology.rocketjump.mountaincore.assets.entities.item;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.junit.Before;
import org.junit.Test;
import technology.rocketjump.mountaincore.assets.entities.EntityAssetTypeDictionary;
import technology.rocketjump.mountaincore.assets.entities.item.model.ItemEntityAsset;
import technology.rocketjump.mountaincore.assets.entities.model.EntityAssetType;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemType;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemTypeDictionary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An item picks its sprite from its stack size, so every size it can reach needs to be covered.
 */
public class ItemEntityAssetLookupTest {

	private ItemEntityAssetDictionary assetDictionary;
	private ItemTypeDictionary itemTypeDictionary;
	private EntityAssetType itemBaseLayer;

	@Before
	public void setUp() throws Exception {
		Injector injector = Guice.createInjector();
		EntityAssetTypeDictionary assetTypeDictionary = injector.getInstance(EntityAssetTypeDictionary.class);
		itemTypeDictionary = injector.getInstance(ItemTypeDictionary.class);
		itemBaseLayer = assetTypeDictionary.getByName("ITEM_BASE_LAYER");

		Path definitions = Paths.get("assets/definitions/entityAssets/itemEntityAssets.json");
		ObjectMapper objectMapper = new ObjectMapper();
		List<ItemEntityAsset> assetList = objectMapper.readValue(Files.readString(definitions),
				objectMapper.getTypeFactory().constructParametrizedType(ArrayList.class, List.class, ItemEntityAsset.class));

		assetDictionary = new ItemEntityAssetDictionary(assetList, assetTypeDictionary, itemTypeDictionary);
	}

	private ItemEntityAttributes pileOf(ItemType itemType, int quantity) {
		ItemEntityAttributes attributes = new ItemEntityAttributes(1L);
		attributes.setItemType(itemType);
		attributes.setQuantity(quantity);
		return attributes;
	}

	@Test
	public void rawStoneHasASpriteForEveryStackSizeItCanReach() {
		ItemType rawStone = itemTypeDictionary.getByName("Resource-Stone-Unrefined");
		assertThat(rawStone).isNotNull();

		for (int quantity = 1; quantity <= rawStone.getMaxStackSize(); quantity++) {
			assertThat(assetDictionary.getItemEntityAsset(itemBaseLayer, pileOf(rawStone, quantity)))
					.as("sprite for a pile of %d raw stone, which may stack to %d", quantity, rawStone.getMaxStackSize())
					.isNotNull();
		}
	}

	@Test
	public void everyItemTypeHasASpriteForEveryStackSizeItCanReach() {
		StringBuilder gaps = new StringBuilder();

		for (ItemType itemType : itemTypeDictionary.getAll()) {
			if (assetDictionary.getItemEntityAsset(itemBaseLayer, pileOf(itemType, 1)) == null) {
				continue; // draws nothing even on its own, which is a different question
			}
			for (int quantity = 2; quantity <= itemType.getMaxStackSize(); quantity++) {
				if (assetDictionary.getItemEntityAsset(itemBaseLayer, pileOf(itemType, quantity)) == null) {
					gaps.append("\n  ").append(itemType.getItemTypeName())
							.append(" draws nothing at ").append(quantity)
							.append(" of a possible ").append(itemType.getMaxStackSize());
					break;
				}
			}
		}

		assertThat(gaps.toString()).as("item types whose art does not cover the stacks they are allowed to form").isEmpty();
	}
}
