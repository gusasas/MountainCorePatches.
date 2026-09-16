package technology.rocketjump.mountaincore.entities.components.creature;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static technology.rocketjump.mountaincore.entities.components.creature.SteeringComponent.MAX_DISTANCE_WITHIN_TILE_TO_ARRIVE;
import static technology.rocketjump.mountaincore.entities.components.creature.SteeringComponent.arrivalSpeed;

/**
 * On the last leg of a walk, what matters is how far one update carries the settler.
 */
public class SteeringArrivalTest {

	private static final float SETTLER_SPEED = 1.8f; // LocationComponent's default
	private static final float ONE_FRAME = 1f / 60f;
	private static final float FASTEST_GAME_SPEED = 7f; // GameSpeed.SPEED4
	private static final int GIVE_UP_AFTER = 600;

	/**
	 * Walks towards the destination one update at a time, turning to face it again after every step,
	 * and returns how many steps it took to arrive, or -1 for never.
	 */
	private int stepsUntilArrival(float startingDistance, float deltaTime, boolean easeIntoIt) {
		float remaining = startingDistance;
		for (int step = 1; step <= GIVE_UP_AFTER; step++) {
			float speed = easeIntoIt ? arrivalSpeed(SETTLER_SPEED, Math.abs(remaining), deltaTime) : SETTLER_SPEED;
			remaining -= Math.signum(remaining) * speed * deltaTime;
			if (Math.abs(remaining) < MAX_DISTANCE_WITHIN_TILE_TO_ARRIVE) {
				return step;
			}
		}
		return -1;
	}

	@Test
	public void atNormalSpeedTheSettlerLandsOnTheSpotWithoutHelp() {
		assertThat(stepsUntilArrival(1f, ONE_FRAME, false)).isPositive();
	}

	@Test
	public void atTheFastestGameSpeedTheSettlerCirclesTheSpotForEver() {
		float deltaTime = ONE_FRAME * FASTEST_GAME_SPEED;
		// One step now covers more than a fifth of a tile, well over the window to land in
		assertThat(SETTLER_SPEED * deltaTime).isGreaterThan(MAX_DISTANCE_WITHIN_TILE_TO_ARRIVE);

		// A tenth of a tile out, it overshoots to eleven hundredths the other side, is turned round,
		// and comes back to where it was. Neither end of that is close enough to count as arrived
		assertThat(stepsUntilArrival(0.1f, deltaTime, false)).isEqualTo(-1);
	}

	@Test
	public void easingIntoTheLastStepLandsOnTheSpotAtEveryGameSpeed() {
		for (float gameSpeed : new float[]{1f, 2f, 4f, 7f, 18f}) {
			for (float frameRate : new float[]{60f, 30f, 15f}) {
				float deltaTime = gameSpeed / frameRate;
				assertThat(stepsUntilArrival(0.1f, deltaTime, true))
						.as("game speed %s at %s frames a second", gameSpeed, frameRate)
						.isPositive();
			}
		}
	}

	@Test
	public void aStepNeverCarriesTheSettlerPastWhereItIsHeaded() {
		float deltaTime = ONE_FRAME * FASTEST_GAME_SPEED;
		for (float distance = 0.01f; distance < 1f; distance += 0.01f) {
			float travelled = arrivalSpeed(SETTLER_SPEED, distance, deltaTime) * deltaTime;
			assertThat(travelled).as("distance left %s", distance).isLessThanOrEqualTo(distance + 1e-5f);
		}
	}

	@Test
	public void ordinaryWalkingIsLeftAlone() {
		// Anything further off than a single step still moves at full speed
		assertThat(arrivalSpeed(SETTLER_SPEED, 5f, ONE_FRAME)).isEqualTo(SETTLER_SPEED);
		assertThat(arrivalSpeed(SETTLER_SPEED, 5f, ONE_FRAME * FASTEST_GAME_SPEED)).isEqualTo(SETTLER_SPEED);
	}

	@Test
	public void aPausedUpdateDoesNotDivideByZero() {
		assertThat(arrivalSpeed(SETTLER_SPEED, 0.5f, 0f)).isEqualTo(SETTLER_SPEED);
	}
}
