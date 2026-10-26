//! Choosing the bitrate while casting: when the connection cannot keep up (frames pile up and
//! are dropped) the bitrate goes down by a third, and it creeps back up when things stay calm.

use std::collections::VecDeque;
use std::time::{Duration, Instant};

/// Drops within [`WINDOW`] that make the connection "behind".
const DROPS_TO_REACT: usize = 6;
const WINDOW: Duration = Duration::from_secs(4);
/// Between two changes, so each has time to show its effect.
const COOLDOWN: Duration = Duration::from_secs(3);
/// Calm for this long before trying a higher bitrate again.
const CALM: Duration = Duration::from_secs(20);

pub struct RateControl {
    target: u32,
    current: u32,
    floor: u32,
    drops: VecDeque<Instant>,
    last_drop: Option<Instant>,
    last_change: Instant,
}

impl RateControl {
    /// `target` is what the person asked for; it is also the most that is ever used.
    pub fn new(target: u32, now: Instant) -> RateControl {
        let floor = (target / 6).max(1_500_000).min(target);
        RateControl { target, current: target, floor, drops: VecDeque::new(), last_drop: None, last_change: now }
    }

    pub fn current(&self) -> u32 {
        self.current
    }

    /// A picture could not be sent in time and was dropped.
    pub fn dropped(&mut self, now: Instant) {
        self.drops.push_back(now);
        self.last_drop = Some(now);
    }

    /// Call every now and then; the new bitrate when it should change.
    pub fn adjust(&mut self, now: Instant) -> Option<u32> {
        while self.drops.front().is_some_and(|&t| now.duration_since(t) > WINDOW) {
            self.drops.pop_front();
        }
        if now.duration_since(self.last_change) < COOLDOWN {
            return None;
        }
        if self.drops.len() >= DROPS_TO_REACT && self.current > self.floor {
            self.current = (self.current as u64 * 7 / 10).max(self.floor as u64) as u32;
            self.drops.clear();
            self.last_change = now;
            return Some(self.current);
        }
        let calm = self.last_drop.map_or(true, |t| now.duration_since(t) >= CALM);
        if self.current < self.target && calm && now.duration_since(self.last_change) >= CALM {
            self.current = (self.current as u64 * 115 / 100).min(self.target as u64) as u32;
            self.last_change = now;
            return Some(self.current);
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn secs(start: Instant, s: f64) -> Instant {
        start + Duration::from_secs_f64(s)
    }

    fn drops(rc: &mut RateControl, start: Instant, from: f64, count: usize) {
        for i in 0..count {
            rc.dropped(secs(start, from + i as f64 * 0.1));
        }
    }

    #[test]
    fn a_calm_connection_keeps_the_target() {
        let t0 = Instant::now();
        let mut rc = RateControl::new(8_000_000, t0);
        for s in 0..120 {
            assert_eq!(rc.adjust(secs(t0, s as f64)), None);
        }
        assert_eq!(rc.current(), 8_000_000);
    }

    #[test]
    fn a_few_drops_are_ignored_many_are_not() {
        let t0 = Instant::now();
        let mut rc = RateControl::new(8_000_000, t0);
        drops(&mut rc, t0, 5.0, 3);
        assert_eq!(rc.adjust(secs(t0, 6.0)), None);
        drops(&mut rc, t0, 6.0, 4);
        assert_eq!(rc.adjust(secs(t0, 7.0)), Some(5_600_000));
    }

    #[test]
    fn it_steps_down_but_waits_between_steps_and_stops_at_the_floor() {
        let t0 = Instant::now();
        let mut rc = RateControl::new(8_000_000, t0);
        let mut at = 4.0;
        drops(&mut rc, t0, at, 8);
        assert_eq!(rc.adjust(secs(t0, at + 1.0)), Some(5_600_000));
        // More trouble right away: not before the cool-down.
        drops(&mut rc, t0, at + 1.0, 8);
        assert_eq!(rc.adjust(secs(t0, at + 2.0)), None);
        at += 5.0;
        drops(&mut rc, t0, at, 8);
        assert_eq!(rc.adjust(secs(t0, at + 1.0)), Some(3_920_000));
        // Keeps going down to the floor (a sixth of the target, at least 1.5 Mbit/s), never below.
        let mut last = 3_920_000;
        for _ in 0..10 {
            at += 5.0;
            drops(&mut rc, t0, at, 8);
            if let Some(b) = rc.adjust(secs(t0, at + 1.0)) {
                assert!(b < last && b >= 1_500_000);
                last = b;
            }
        }
        assert_eq!(rc.current(), 1_500_000);
        at += 5.0;
        drops(&mut rc, t0, at, 8);
        assert_eq!(rc.adjust(secs(t0, at + 1.0)), None);
    }

    #[test]
    fn it_comes_back_up_slowly_when_calm_and_never_above_the_target() {
        let t0 = Instant::now();
        let mut rc = RateControl::new(8_000_000, t0);
        drops(&mut rc, t0, 4.0, 8);
        assert_eq!(rc.adjust(secs(t0, 5.0)), Some(5_600_000));
        // Not for 20 s after the last drop, then a step of 15 % every 20 s.
        assert_eq!(rc.adjust(secs(t0, 20.0)), None);
        assert_eq!(rc.adjust(secs(t0, 25.0)), Some(6_440_000));
        assert_eq!(rc.adjust(secs(t0, 30.0)), None);
        assert_eq!(rc.adjust(secs(t0, 45.0)), Some(7_406_000));
        assert_eq!(rc.adjust(secs(t0, 65.0)), Some(8_000_000));
        assert_eq!(rc.adjust(secs(t0, 90.0)), None);
        assert_eq!(rc.current(), 8_000_000);
    }

    #[test]
    fn a_drop_while_recovering_delays_the_next_step() {
        let t0 = Instant::now();
        let mut rc = RateControl::new(8_000_000, t0);
        drops(&mut rc, t0, 4.0, 8);
        rc.adjust(secs(t0, 5.0));
        rc.dropped(secs(t0, 24.0));
        assert_eq!(rc.adjust(secs(t0, 26.0)), None);
        assert_eq!(rc.adjust(secs(t0, 45.0)), Some(6_440_000));
    }

    #[test]
    fn a_low_target_is_not_pushed_below_itself() {
        let t0 = Instant::now();
        let mut rc = RateControl::new(1_000_000, t0);
        drops(&mut rc, t0, 4.0, 8);
        assert_eq!(rc.adjust(secs(t0, 5.0)), None);
        assert_eq!(rc.current(), 1_000_000);
    }
}
