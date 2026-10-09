package technology.rocketjump.mountaincore.entities.behaviour.furniture;

import org.junit.Test;
import org.mockito.Mockito;
import technology.rocketjump.mountaincore.entities.components.InventoryComponent;
import technology.rocketjump.mountaincore.environment.GameClock;
import technology.rocketjump.mountaincore.gamecontext.GameContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The tank holds two at a time, and the point of these is that the two do not have to arrive
 * together or come out together - a player with a single bundle left must not be stuck waiting
 * for a second one that never comes.
 */
public class RettingTankSoakingTest {

	private static final double SOAK_HOURS = 24.0;

	private MushroomShockTankBehaviour tank(double soakHours) {
		MushroomShockTankBehaviour behaviour = new MushroomShockTankBehaviour();
		behaviour.TIME_TO_SHOCK_MUSHROOM_LOG = soakHours;
		return behaviour;
	}

	private GameContext atHour(double hour) {
		GameClock clock = Mockito.mock(GameClock.class);
		when(clock.getCurrentGameTime()).thenReturn(hour);
		GameContext gameContext = new GameContext();
		gameContext.setGameClock(clock);
		return gameContext;
	}

	private InventoryComponent.InventoryEntry wentInAt(double hour) {
		return new InventoryComponent.InventoryEntry(null, hour, null);
	}

	@Test
	public void eachItemIsTimedFromWhenItWentIn() {
		MushroomShockTankBehaviour tank = tank(SOAK_HOURS);
		GameContext now = atHour(100.0);

		assertThat(tank.soakingProgress(wentInAt(100.0), now)).isEqualTo(0f);
		assertThat(tank.soakingProgress(wentInAt(88.0), now)).isEqualTo(0.5f);
		assertThat(tank.soakingProgress(wentInAt(76.0), now)).isEqualTo(1f);
	}

	@Test
	public void twoItemsPutInAtDifferentTimesFinishAtDifferentTimes() {
		MushroomShockTankBehaviour tank = tank(SOAK_HOURS);
		InventoryComponent.InventoryEntry first = wentInAt(0.0);
		InventoryComponent.InventoryEntry second = wentInAt(12.0);

		// the moment the first one is done, the second is only half way
		GameContext firstIsDone = atHour(24.0);
		assertThat(tank.soakingProgress(first, firstIsDone)).isEqualTo(1f);
		assertThat(tank.soakingProgress(second, firstIsDone)).isLessThan(1f);

		// and the second finishes later, on its own clock
		GameContext secondIsDone = atHour(36.0);
		assertThat(tank.soakingProgress(second, secondIsDone)).isEqualTo(1f);
	}

	@Test
	public void aSingleItemIsNeverHeldBackWaitingForACompanion() {
		MushroomShockTankBehaviour tank = tank(SOAK_HOURS);
		// one bundle, nothing beside it, still soaks and still finishes
		assertThat(tank.soakingProgress(wentInAt(0.0), atHour(24.0))).isEqualTo(1f);
	}

	@Test
	public void progressNeverRunsBackwardsOrPastTheEnd() {
		MushroomShockTankBehaviour tank = tank(SOAK_HOURS);
		// an item whose timestamp is somehow in the future reads as not started, not as negative
		assertThat(tank.soakingProgress(wentInAt(200.0), atHour(100.0))).isEqualTo(0f);
		// and one left in far longer than needed stays at full, it does not keep climbing
		assertThat(tank.soakingProgress(wentInAt(0.0), atHour(1000.0))).isEqualTo(1f);
	}

	@Test
	public void theTankDeclaresTheCapacityThePanelDraws() {
		assertThat(tank(SOAK_HOURS).getItemCapacity()).isEqualTo(2);
	}
}
