import {Injectable} from "@angular/core";
import {AlbumsPage, Album, Artist, Track} from "../models/album";
import identity from "lodash/identity";
import {HttpClient} from "@angular/common/http";
import "rxjs/add/operator/toPromise";
import "rxjs/add/operator/map";
import {AppConfig} from "../app.config";
import {Observable} from "rxjs";

const PAGE_SIZE = 12;

export const SORT_OPTIONS = [
  {
    label:'No sorting',
    value: 'NO_SORTING'
  },
  {
    label:'Title A-z',
    value: 'TITLE_ASC'
  },
  {
    label:'Title z-A',
    value: 'TITLE_DESC'
  },
  {
    label: 'Newest first',
    value: 'RELEASE_DESC'
  },
  {
    label: 'Oldest first',
    value: 'RELEASE_ASC'
  },
  {
    label: 'Newest First, Title A-z',
    value: 'RELEASE_DESC_TITLE_ASC'
  },
  {
    label: 'Newest First, Title z-A',
    value: 'RELEASE_DESC_TITLE_DESC'
  },
  {
    label: 'Oldest first, Title A-z',
    value: 'RELEASE_ASC_TITLE_ASC'
  },
  {
    label: 'Oldest first, Title z-A',
    value: 'RELEASE_ASC_TITLE_DESC'
  }
];

const SORT_HASH = {
  'NO_SORTING': identity,
  'RELEASE_DESC': (url) => `${url}&sort=desc,released_date`,
  'RELEASE_ASC': (url) => `${url}&sort=asc,released_date`,
  'TITLE_ASC': (url) => `${url}&sort=asc,name`,
  'TITLE_DESC': (url) => `${url}&sort=desc,name`,
  'RELEASE_DESC_TITLE_ASC': (url) => SORT_HASH.TITLE_ASC(SORT_HASH.RELEASE_DESC(url)),
  'RELEASE_DESC_TITLE_DESC': (url) => SORT_HASH.TITLE_DESC(SORT_HASH.RELEASE_DESC(url)),
  'RELEASE_ASC_TITLE_ASC': (url) => SORT_HASH.TITLE_ASC(SORT_HASH.RELEASE_ASC(url)),
  'RELEASE_ASC_TITLE_DESC': (url) => SORT_HASH.TITLE_DESC(SORT_HASH.RELEASE_ASC(url))
};

interface PageRequest {
  pageNumber: number,
  sortType: string
}

const mapToArtist = ({
  _id,
  name
}): Artist => ({
  id: _id,
  name
});

const mapToTrack = ({
  id,
  name,
  length,
  position
}): Track  => ({
  id,
  duration: length ? `${length}` + '' : '0',
  name,
  position
});

const mapToAlbum = ({
  _id,
  name,
  cover_image_url,
  country,
  artists,
  style,
  format,
  tracks,
  slug
}): Album => ({
  id: _id,
  title: name,
  coverImageURL: cover_image_url,
  country,
  style,
  format,
  slug,
  trackList: tracks
    ? tracks.map(mapToTrack)
    : [],
  artists: artists
    ? artists.map(mapToArtist)
    : []
});

const mapToTrackRequest = ({
  id,
  name,
  duration,
  position
}: Track) => ({
  id,
  length: duration,
  name,
  position
});

const mapToArtistRequest = ({
  id,
  name
}: Artist) => ({
  _id: id,
  name
});

const mapToAlbumRequest = ({
  title,
  coverImageURL,
  country,
  style,
  format,
  slug,
  trackList,
  artists
}: Album) => ({
  name: title,
  cover_image_url: coverImageURL,
  country,
  style,
  format,
  slug,
  artists: artists.map(mapToArtistRequest),
  tracks: trackList.map(mapToTrackRequest)
});

@Injectable()
export class AlbumService {

  constructor(
    private http: HttpClient,
    private config: AppConfig
  ) {
  }

/**
 * @desc returns URL for albums page request
 * */
  getAlbumsPageURL({pageNumber, sortType}: PageRequest): string {
    const url = `${this.config.apiURL}/api/1.0/albums?page=${pageNumber}&per_page=${PAGE_SIZE}`;
    return SORT_HASH[sortType](url);
  }

/**
 * @desc get albums page from server side
 * */
  getAlbumsPage(request: PageRequest): Promise<AlbumsPage> {
    return this.http.get(this.getAlbumsPageURL(request))
      .map((response: any) => {
        console.log('Albums: ', response);
        const albums = response.results.map(mapToAlbum);
        return {
          albums,
          totalNumber: response.pagination.items
        };
      })
      .toPromise();
  }

/**
 * @desc get album by slug URL
 * */
  getAlbumBySlugURL(albumSlug: string): string {
    return `${this.config.apiURL}/api/1.0/albums/slug/${albumSlug}`;
  }

/**
 * @desc get album by slug from server side
 * */
  getAlbumBySlug(albumSlug: string):Promise<Album> {
    return this.http.get(this.getAlbumBySlugURL(albumSlug))
      .map((response: any) => {
        console.log('Album: ', response);
        return mapToAlbum(response);
      })
      .toPromise();
  }

  deleteTrackInAlbum(albumId: string, trackId: string): Promise<Object> {
    return this.http.delete(`${this.config.apiURL}/api/1.0/albums/${albumId}/tracks/${trackId}`)
      .toPromise()
  }

  saveAlbumTracks(albumId: string, tracks: Array<Track>): Promise<Object> {
    return this.http.put(`${this.config.apiURL}/api/1.0/albums/${albumId}/tracks`, tracks)
      .toPromise()
  }

  updateAlbumTrack(albumId: string, track: Track): Promise<Object> {
    return this.http.put(`${this.config.apiURL}/api/1.0/albums/${albumId}/tracks/${track.id}`, track)
      .toPromise();
  }

  addTrackToAlbum(albumId: string, track: Track): Promise<Track> {
    const request = mapToTrackRequest(track);
    return this.http.post(`${this.config.apiURL}/api/1.0/albums/${albumId}/tracks/`, request)
      .map((response) => {
        return mapToTrack(response as any);
      })
      .toPromise();
  }

  searchForArtists(query: string): Observable<Array<Artist>> {
    return this.http
      .get(`${this.config.apiURL}/api/1.0/artists/search?name_entry=${query}&limit=5`)
      .map((response: any) => {
        console.log('Search response: ', response);
        return response.map(mapToArtist);
      });
  }

  createNewAlbum(album: Album): Promise<Album> {
    return this.http
      .post(`${this.config.apiURL}/api/1.0/albums/`, mapToAlbumRequest(album))
      .map((response: any) => {
        console.log('Creation response: ', response);
        return mapToAlbum(response);
      })
      .toPromise()
  }

  updateAlbum(album: Album): Promise<Album> {
    return this.http
      .put(`${this.config.apiURL}/api/1.0/albums/${album.id}`, mapToAlbumRequest(album))
      .map((response: any) => {
        console.log('Updated response: ', response);
        return mapToAlbum(response);
      })
      .toPromise();
  }
}
