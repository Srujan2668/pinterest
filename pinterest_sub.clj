
(config
  (text-field
    :name "clientId"
    :label "Client Id"
    :placeholder "Client Id goes here"
    :required true
    (api-config-field))

  (password-field
    :name "clientSecret"
    :label "Client Secret"
    :placeholder "Client Secret goes here"
    :required true
    (api-config-field :masked true))

  (oauth2/authorization-code-with-client-credentials
    (authorization-code
      (source
        (http/get
          :base-url ""
          :url "https://www.pinterest.com/oauth/"
          (query-params
            "response_type" "code"
            "client_id" "{clientId}"
            "scope" "boards:read,pins:read"
            "redirect_uri" "https://www.fivetran.com/oauth/pinterest/oauth_response/"))))

    (access-token
      (source
        (http/post
          :base-url ""
          :url "https://api.pinterest.com/v5/oauth/token"
          (header-param
            "Authorization" "Basic BASE_64({clientId}:{clientSecret})"
            "Content-Type" "application/x-www-form-urlencoded")
          (body-params
            "code" "$AUTHORIZATION-CODE"
            "grant_type" "authorization_code"
            "redirect_uri" "https://www.example.com/oauth/pinterest/oauth_response/")))
      (fields
        access_token :<= "access_token"
        refresh_token :<= "refresh_token"
        token_type :<= "token_type"
        scope :<= "scope"
        realm_id :<= "realm-id"
        expires_at :<= "expires_in"))

    (refresh-token
      (source
        (http/post
          :base-url ""
          :url "https://api.pinterest.com/v5/oauth/token"
          (header-param
            "Authorization" "Basic BASE_64({clientId}:{clientSecret})"
            "Content-Type" "application/x-www-form-urlencoded")
          (body-params
            "refresh_token" "$REFRESH-TOKEN"
            "grant_type" "refresh_token")))
      (fields
        refresh_token :<= "refresh_token"
        access_token :<= "access_token"))))

(default-source
  (http/get :base-url "https://api.pinterest.com/v5"
    (header-params "Accept" "application/json"))
  (paging/key-based :scroll-key-query-param-name "bookmark"
                    :scroll-value-path-in-response "bookmark"
                    :limit 100
                    :limit-query-param-name "limit")
  (auth/oauth2)
  (error-handler
    (when :status 401 :action refresh)
    (when :status 429 :action rate-limit)))

(temp-entity PIN
  (api-docs-url "https://developers.pinterest.com/docs/api/v5/pins-list")
  (source (http/get :url "/pins")
    (setup-test
      (upon-receiving :code 200 (pass)))
    (extract-path "items"))
  (fields
    id :id
    created_at
    link
    title
    description
    alt_text
    creative_type
    board_id
    board_section
    is_owner
    parent_pin_id
    is_standard
    has_been_promoted
    note)
  (dynamic-fields
    (custom-fields/key-value :from "media" :prefix "media_"))
  (relate
    (contains-list-of PIN_METRICS :inside-prop "pin_metrics.pin_metrics")))

(entity PIN_METRICS
  (fields
    pin_click
    impression
    clickthrough
    reaction
    comment)
  (relate
    (needs PIN :inside-prop "id")))

(entity PIN_ANALYTICS
  (api-doc-url "https://developers.pinterest.com/docs/api/v5/pins-analytics")
  (source (http/get "/pins/{pin_id}/analytics")
    (extract-path "property1.daily_metrics"))
  (fields
    date_status
    date)
  (dynamic-fields
    (flatten-fields
      (fields
        IMPRESSION
        OUTBOUND_CLICK
        PIN_CLICK
        QUARTILE_95_PERCENT_VIEW
        SAVE
        SAVE_RATE
        VIDEO_10S_VIEW
        VIDEO_AVG_WATCH_TIME
        VIDEO_MRC_VIEW
        VIDEO_START
        VIDEO_V50_WATCH_TIME)
      :from "metrics"))
  (relate
    (links-to PIN :inside-prop "id"))
  (syn-plan
    (change-capture-cursor ""
      (subset/by-time
        (query-params "startDate" "$FROM" 
                      "endDate" "$TO"))
      (format "yyyy-MM-dd")
      (step-size "90d")
      (since-last "90d")
      (save))))

(entity BOARDS
  (api-docs-url "https://developers.pinterest.com/docs/api/v5/boards-list")
  (source (http/get :url "/boards")
    (extract-path "items"))
  (fields
    id
    created_at
    board_pins_modified_at
    name
    description
    collaborator_count
    pin_count
    follower_count
    privacy)
  (dynamic-fields
    (flatten-fields
      (fields
        image_cover_url)
      :from "media"))
  (relate
    (contains-list-of PIN_THUMBNAIL_URLS :inside-prop "pin_thumbnail_urls" :as "pin_thumbnails"))
  (dynamic-fields
    (flatten-fields
      (fields
        username)
      :from "owner")))

(entity PIN_THUMBNAIL_URLS
  (fields
    pin_thumbnails)
  (relate
    (needs BOARDS :inside-prop "id")))

(entity MEDIA
  (api-docs-url "https://developers.pinterest.com/docs/api/v5/media-list")
  (source (http/get :url "/media")
    (extract-path "items"))
  (fields
    media_id
    media_type
    status))

(entity USER_ACCOUNT
  (api-docs-url "https://developers.pinterest.com/docs/api/v5/user_account-get")
  (source (http/get :url "/user_account")
    (extract-path "items"))
  (fields
    account_type
    id :id
    profile_image
    website_url
    username
    about
    business_name
    board_count
    pin_count
    follower_count
    following_count
    monthly_views))

(entity USER_ACCOUNT_ANALYTICS
  (api-docs-url "https://developers.pinterest.com/docs/api/v5/user_account-analytics")
  (source (http/get :url "/user_account/analytics")
    (extract-path "items"))
  (fields
    data_status
    date)
  (dynamic-fields
    (flatten-fields
      (fields
        CLOSEUP
        CLOSEUP_RATE
        ENGAGEMENT
        ENGAGEMENT_RATE
        IMPRESSION
        OUTBOUND_CLICK
        OUTBOUND_CLICK_RATE
        PIN_CLICK
        PIN_CLICK_RATE
        QUARTILE_95_PERCENT_VIEW
        SAVE
        SAVE_RATE
        VIDEO_10S_VIEW
        VIDEO_AVG_WATCH_TIME
        VIDEO_MRC_VIEW
        VIDEO_START
        VIDEO_V50_WATCH_TIME)))
  (relate
    (needs USER_ACCOUNT :inside-prop "id"))
  (syn-plan
    (change-capture-cursor ""
      (subset/by-time
        (query-params "startDate" "$FROM" "endDate" "$TO"))
      (format "yyyy-MM-dd")
      (step-size "90d")
      (since-last "90d")
      (save))))

(entity AD_ACCOUNTS
  (api-docs-url "https://developers.pinterest.com/docs/api/v5/ad_accounts-list")
  (source (http/get :url "/ad_accounts")
    (extract-path "items"))
  (fields
    id :id
    name
    country
    currency
    created_time
    updated_time)
  (dynamic-fields
    (flatten-fields
      (fields
        username
        id)
      :from "owner"))
  (relate
    (contains-list PERMISSION :inside-prop "permissions" :as "permissions_a")))

(entity PERMISSION
  (fields
    permissions_a)
  (relate
    (needs AD_ACCOUNTS :inside-prop "id")))

(entity LIST_TEMPLATES
  (api-docs-url "https://developers.pinterest.com/docs/api/v5/templates-list")
  (source (http/get :url "/ad_accounts/{ad_account_id}/templates")
    (extract-path "items"))
  (fields
    id
    ad_account_id
    name
    status
    tracking_url(json)
    starting_time
    end_time
    objective_type
    created_time
    updated_time
    campaign_id)
  (relate
    (links-to AD_ACCOUNTS :inside-prop "ad_account_id")))

(entity LIST_CAMPAIGNS
  (api-docs-url "https://developers.pinterest.com/docs/api/v5/campaigns-list")
  (source (http/get :url "/ad_accounts/{ad_account_id}/campaigns")
    (extract-path "items"))
  (fields
    id
    ad_account_id
    name
    status
    lifetime_spend_cap
    daily_spend_cap
    order_line_id
    tracking_url(json)
    starting_time
    end_time
    objective_type
    created_time
    updated_time
    type
    is_flexible_daily_budgets
    is_campaign_budget_optimization
    summary_status)
  (relate
    (links-to AD_ACCOUNTS :inside-prop "ad_account_id")))

(entity LIST_AD_GROUPS
  (api-docs-url "https://developers.pinterest.com/docs/api/v5/ad-groups-list")
  (source (http/get :url "/ad_accounts/{ad_account_id}/ad_groups")
    (extract-path "items"))
  (fields
    id :id
    ad_account_id
    name
    status
    budget_in_micro_currency
    bid_in_micro_currency
    budget_type
    start_time
    end_time
    targeting_spec (json)
    lifetime_frequency_cap
    tracking_urls (json)
    auto_targeting_enabled
    placement_group
    pacing_delivery_type
    campaign_id
    billable_event
    bid_strategy_type
    targeting_template_ids
    created_time
    updated_time
    type
    conversion_learning_mode_type
    summary_status
    feed_profile_id
    dca_assets)
  (dynamic-fields
    (flatten-fields
      (fields
        conversion_event
        conversion_tag_id
        cpa_goal_value_in_micro_currency
        is_roas_optimized
        learning_mode_type)
      :from "optimization_goal_metadata/conversion_tag_v3_goal_metadata")
    (flatten-fields
      (fields
        frequency
        timerange)
      :from "optimization_goal_metadata/frequency_goal_metadata")
    (flatten-fields
      (fields
        scrollup_goal_value_in_micro_currency)
      :from "optimization_goal_metadata/scrollup_goal_metadata")
    (flatten-fields
      (fields
        lookback_window
        exclusion_window
        tag_types)
      :from "targeting_spec/SHOPPING_RETARGETING"))
  (relate
    (links-to AD_ACCOUNTS :inside-prop "ad_account_id")))

(entity LIST_ADS
  (api-docs-url "https://developers.pinterest.com/docs/api/v5/ads-list")
  (source (http/get :url "/ad_accounts/{ad_account_id}/ads")
    (extract-path "items"))
  (fields
    id :id
    ad_account_id
    name
    tracking_url (json)
    created_time
    updated_time
    campaign_id
    ad_group_id
    status
    pin_id
    rejection_reason
    type
    summary_status
    campaign_summary_status
    is_spend_in_pacing_control)
  (dynamic-fields
    (flatten-fields
      (fields
        event_type
        summary_status
        id)
      :from "creative_type"))
  (relate
    (links-to AD_ACCOUNTS :inside-prop "ad_account_id")))

