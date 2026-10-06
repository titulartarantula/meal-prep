import pytest
from mealprep.exchange import durations


@pytest.mark.parametrize("v, want", [
    ("PT90M", 90), ("PT1H30M", 90), ("P0DT0H20M", 20), ("PT1H30M15S", 90), ("PT1H30M45S", 91), ("pt45m", 45),
    ("P1DT2H", 1560), ("PT0.5H", 30), ("PT", None), ("PT0M", None), ("P", None),
    ("20 minutes", 20), ("1 hr 15 min", 75), ("1h30", 90), ("1 1/2 hours", 90), ("1½ hours", 90), ("90", 90),
    ("1 hour 30 minutes", 90), ("45 mins", 45), ("20-25 minutes", 25), ("20 to 25 min", 25), ("2 hrs", 120),
    ("1.5 hours", 90), ("About 10 minutes", 10), ("overnight", None), ("", None), (None, None), ("garbage", None),
    (90, 90), (12.4, 12), (0, None), (-5, None), (True, None), ("PT99999H", None), ({"a": 1}, None), ([], None),
])
def test_parse(v, want):
    assert durations.parse(v) == want


@pytest.mark.parametrize("m, want", [(90, "PT1H30M"), (45, "PT45M"), (60, "PT1H"), (1560, "PT26H"), (None, None),
                                     (0, None), (-3, None)])
def test_to_iso(m, want):
    assert durations.to_iso(m) == want


def test_round_trip():
    for m in range(1, 3000, 7):
        assert durations.parse(durations.to_iso(m)) == m
