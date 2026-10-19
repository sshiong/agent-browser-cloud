# Recording privacy self-test assets

`grace-hopper.jpg` is the unmodified public-domain U.S. Navy portrait credited to
James S. Davis, 20 January 1984, photograph NH 96919-KN. It is used only to test local
face detection and redaction, without identity recognition or external model calls.

Source and public-domain notice:
https://github.com/matplotlib/matplotlib/commit/5da2608950866c9e821177b20b6cfadfd76d3b7a

Exact source file:
https://raw.githubusercontent.com/matplotlib/matplotlib/5da2608950866c9e821177b20b6cfadfd76d3b7a/lib/matplotlib/mpl-data/sample_data/grace_hopper.jpg

The fixture's attribution does not select or change this project's license.

SHA-256: `a8ca6d734765703b09728ab47fe59f473d93ae3967fc24c7c0288c3c7adb7130`.
The scanner verifies these exact bytes before its functional face self-test. Both the
production and offline test images install the image and this attribution together.

The independently reviewed facial test region is `(left=156, top=140, right=374,
bottom=345)` in the original 512×600 image. Readiness checks both detection coverage
and the decoded, returned JPEG over this fixed region; it is not derived from the
detector's proposed rectangles. Face masks add a bounded 10% margin before clipping.
