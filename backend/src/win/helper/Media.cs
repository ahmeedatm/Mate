// Lecture en cours : contrôles multimédias de Windows (ceux du panneau de volume), où le navigateur publie YouTube,
// YouTube Music, Twitch, et Spotify aussi. API WinRT, appelée directement (métadonnées de Microsoft.Windows.SDK.Contracts).

using System;
using System.Collections.Generic;
using System.IO;
using Windows.Foundation;
using Windows.Media.Control;

namespace Companion
{
    static class Media
    {
        static GlobalSystemMediaTransportControlsSessionManager manager;

        static T Await<T>(IAsyncOperation<T> operation)
        {
            var task = operation.AsTask();
            if (!task.Wait(5000)) throw new TimeoutException("Windows ne répond pas (lecture en cours)");
            return task.Result;
        }

        static GlobalSystemMediaTransportControlsSessionManager Manager()
        {
            if (manager == null) manager = Await(GlobalSystemMediaTransportControlsSessionManager.RequestAsync());
            return manager;
        }

        // Session de l'appli app (identifiant Windows de l'appli), sinon celle que Windows met en avant.
        static GlobalSystemMediaTransportControlsSession Find(string app)
        {
            if (!string.IsNullOrEmpty(app))
                foreach (var s in Manager().GetSessions())
                    if (s.SourceAppUserModelId == app) return s;
            var current = Manager().GetCurrentSession();
            if (current == null) throw new Exception("Rien en lecture sur l'ordinateur");
            return current;
        }

        static List<object> List()
        {
            var current = Manager().GetCurrentSession();
            var list = new List<object>();
            foreach (var s in Manager().GetSessions())
            {
                var props = Await(s.TryGetMediaPropertiesAsync());
                var playback = s.GetPlaybackInfo();
                var controls = playback.Controls;
                var timeline = s.GetTimelineProperties();
                var status = playback.PlaybackStatus.ToString();
                // Position donnée à l'instant LastUpdatedTime : on ajoute le temps écoulé depuis si la lecture continue.
                var position = timeline.Position;
                if (status == "Playing" && timeline.LastUpdatedTime.Year > 2000) position += DateTimeOffset.Now - timeline.LastUpdatedTime;
                var duration = (timeline.EndTime - timeline.StartTime).TotalMilliseconds;
                object positionMs = null;
                object durationMs = null;
                if (duration > 0)
                {
                    durationMs = (long)duration;
                    positionMs = (long)Math.Max(0, Math.Min(duration, (position - timeline.StartTime).TotalMilliseconds));
                }
                list.Add(new Dictionary<string, object>
                {
                    { "app", s.SourceAppUserModelId ?? "" },
                    { "current", current != null && current.SourceAppUserModelId == s.SourceAppUserModelId },
                    { "title", props.Title ?? "" },
                    { "artist", props.Artist ?? "" },
                    { "album", props.AlbumTitle ?? "" },
                    { "hasThumb", props.Thumbnail != null },
                    { "status", status.ToLowerInvariant() },
                    { "canPlayPause", controls.IsPlayPauseToggleEnabled },
                    { "canNext", controls.IsNextEnabled },
                    { "canPrevious", controls.IsPreviousEnabled },
                    { "canSeek", controls.IsPlaybackPositionEnabled },
                    { "positionMs", positionMs },
                    { "durationMs", durationMs },
                });
            }
            return list;
        }

        // Écrit la vignette (pochette, image de la vidéo) dans path ; false si la session n'en a pas.
        static bool SaveThumb(GlobalSystemMediaTransportControlsSession session, string path)
        {
            var thumb = Await(session.TryGetMediaPropertiesAsync()).Thumbnail;
            if (thumb == null) return false;
            using (var stream = Await(thumb.OpenReadAsync()).AsStreamForRead())
            using (var file = File.Create(path))
                stream.CopyTo(file);
            return true;
        }

        public static object Invoke(Dictionary<string, object> req)
        {
            var app = Native.Str(req, "app");
            switch (Native.Str(req, "action"))
            {
                case "list": return List();
                case "thumb": return SaveThumb(Find(app), Native.Str(req, "out"));
                case "play-pause": return Await(Find(app).TryTogglePlayPauseAsync());
                case "next": return Await(Find(app).TrySkipNextAsync());
                case "previous": return Await(Find(app).TrySkipPreviousAsync());
                case "seek": return Await(Find(app).TryChangePlaybackPositionAsync(Convert.ToInt64(req["position"]) * 10000));
                default: throw new ArgumentException("Action inconnue : " + Native.Str(req, "action"));
            }
        }
    }
}
