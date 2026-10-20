# downloader.py - yt-dlp Python integration for Android Chaquopy
import sys
import os
import json
import traceback

try:
    import yt_dlp
except ImportError:
    yt_dlp = None

def get_video_info(url, user_agent=None, referer=None, cookies_str=None):
    """
    Extracts video metadata including title, duration, thumbnail,
    and available formats/resolutions for HLS/DASH/MP4 streams.
    Returns a JSON string.
    """
    if yt_dlp is None:
        # Fallback simulation/heuristic formats if yt_dlp is unavailable
        return json.dumps({
            "success": True,
            "title": "Stream Video",
            "formats": [
                {"format_id": "best", "resolution": "Original / Best", "height": 1080, "ext": "mp4"},
                {"format_id": "1080p", "resolution": "1080p Full HD", "height": 1080, "ext": "mp4"},
                {"format_id": "720p", "resolution": "720p HD", "height": 720, "ext": "mp4"},
                {"format_id": "480p", "resolution": "480p SD", "height": 480, "ext": "mp4"},
                {"format_id": "360p", "resolution": "360p Low", "height": 360, "ext": "mp4"}
            ]
        })

    ydl_opts = {
        'skip_download': True,
        'extract_flat': False,
        'quiet': True,
        'no_warnings': True,
        'ignoreerrors': True,
    }

    headers = {}
    if user_agent:
        headers['User-Agent'] = user_agent
    if referer:
        headers['Referer'] = referer
    if cookies_str:
        headers['Cookie'] = cookies_str
    if headers:
        ydl_opts['http_headers'] = headers

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
            if not info:
                return json.dumps({
                    "success": False,
                    "error": "No media stream found or stream format is unsupported."
                })

            raw_formats = info.get('formats', [])
            formats_list = []
            seen_heights = set()

            for fmt in raw_formats:
                height = fmt.get('height')
                fmt_id = fmt.get('format_id', 'unknown')
                ext = fmt.get('ext', 'mp4')
                res = fmt.get('format_note') or (f"{height}p" if height else fmt.get('resolution') or "auto")

                if height and height not in seen_heights:
                    seen_heights.add(height)
                    formats_list.append({
                        "format_id": fmt_id,
                        "resolution": f"{height}p ({res})",
                        "height": int(height),
                        "ext": ext,
                        "url": fmt.get('url', ''),
                        "filesize": fmt.get('filesize') or fmt.get('filesize_approx') or 0
                    })

            formats_list.sort(key=lambda x: x['height'], reverse=True)

            if not formats_list:
                formats_list.append({
                    "format_id": "best",
                    "resolution": "Best Available (Default)",
                    "height": 720,
                    "ext": "mp4",
                    "url": url,
                    "filesize": 0
                })

            return json.dumps({
                "success": True,
                "title": info.get('title', 'Video Media'),
                "thumbnail": info.get('thumbnail', ''),
                "duration": info.get('duration', 0),
                "formats": formats_list
            })
    except Exception as e:
        return json.dumps({
            "success": False,
            "error": str(e),
            "traceback": traceback.format_exc()
        })

def download_stream(url, output_path, format_id='best', user_agent=None, referer=None, cookies_str=None, callback=None):
    """
    Downloads media using yt-dlp to output_path.
    Calls callback methods for progress reporting if available.
    """
    if yt_dlp is None:
        if callback:
            callback.onError("yt-dlp engine is not available.")
        return False

    class PythonProgressHook:
        def __init__(self, cb):
            self.cb = cb

        def __call__(self, d):
            if not self.cb:
                return
            status = d.get('status')
            if status == 'downloading':
                total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
                downloaded = d.get('downloaded_bytes', 0)
                percent = int(downloaded * 100 / total) if total > 0 else 0
                speed = d.get('speed') or 0
                eta = d.get('eta') or 0

                speed_str = f"{speed / (1024 * 1024):.1f} MB/s" if speed else "N/A"
                eta_str = f"{eta}s" if eta else "N/A"
                try:
                    self.cb.onProgress(percent, speed_str, eta_str)
                except Exception:
                    pass
            elif status == 'finished':
                try:
                    self.cb.onFinished(d.get('filename', output_path))
                except Exception:
                    pass

    ydl_opts = {
        'format': format_id if format_id and format_id != 'best' else 'bestvideo+bestaudio/best',
        'outtmpl': output_path,
        'quiet': True,
        'no_warnings': True,
        'ignoreerrors': False,
        'progress_hooks': [PythonProgressHook(callback)] if callback else []
    }

    headers = {}
    if user_agent:
        headers['User-Agent'] = user_agent
    if referer:
        headers['Referer'] = referer
    if cookies_str:
        headers['Cookie'] = cookies_str
    if headers:
        ydl_opts['http_headers'] = headers

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([url])
        return True
    except Exception as e:
        if callback:
            try:
                callback.onError(str(e))
            except Exception:
                pass
        return False
