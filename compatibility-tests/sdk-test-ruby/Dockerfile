ARG RUBY_BUILD_IMAGE=ruby:3.4.5
FROM ${RUBY_BUILD_IMAGE}
WORKDIR /app
COPY Gemfile Gemfile.lock ./
RUN bundle config set frozen true && bundle install
COPY test/ test/
ENV FLOCI_GCP_ENDPOINT=http://floci-gcp:4588 FLOCI_GCP_PROJECT=ruby-compat
ENTRYPOINT ["bundle", "exec", "ruby", "-Itest", "test/all.rb"]
